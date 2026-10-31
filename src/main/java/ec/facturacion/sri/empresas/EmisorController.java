package ec.facturacion.sri.empresas;

import ec.facturacion.sri.seguridad.Auditoria;
import ec.facturacion.sri.seguridad.Cifrado;
import ec.facturacion.sri.seguridad.Sesion;
import ec.facturacion.sri.sri.Firmador;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Emisores (RUC) del tenant, su certificado de firma y sus puntos de emisión. */
@RestController
@RequestMapping("/emisores")
public class EmisorController {
    private final JdbcClient db;
    private final Cifrado cifrado;
    private final Auditoria auditoria;
    private final JsonMapper json;

    public EmisorController(JdbcClient db, Cifrado cifrado, Auditoria auditoria, JsonMapper json) {
        this.json = json;
        this.db = db;
        this.cifrado = cifrado;
        this.auditoria = auditoria;
    }

    public record Emisor(@Pattern(regexp = "\\d{10}001", message = "RUC inválido (13 dígitos terminados en 001)") String ruc, @NotBlank String razonSocial, String nombreComercial,
                         @NotBlank String dirMatriz, Boolean obligadoContabilidad, String contribuyenteEspecial,
                         String agenteRetencion, String contribuyenteRimpe) {}

    public record Punto(@Pattern(regexp = "\\d{3}") String estab, @Pattern(regexp = "\\d{3}") String ptoEmi,
                        @NotBlank String dirEstablecimiento) {}

    private static final String COLUMNAS = """
            id, ruc, razon_social as "razonSocial", nombre_comercial as "nombreComercial", dir_matriz as "dirMatriz",
            obligado_contabilidad as "obligadoContabilidad", contribuyente_especial as "contribuyenteEspecial",
            agente_retencion as "agenteRetencion", contribuyente_rimpe as "contribuyenteRimpe",
            cert_sujeto as "certSujeto", cert_expira as "certExpira", activo""";

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Emisor e) {
        Sesion s = Sesion.de(jwt);
        try {
            long id = db.sql("""
                            insert into emisor (tenant_id, ruc, razon_social, nombre_comercial, dir_matriz, obligado_contabilidad,
                                                contribuyente_especial, agente_retencion, contribuyente_rimpe)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(s.tenant(), e.ruc(), e.razonSocial(), e.nombreComercial(), e.dirMatriz(), Boolean.TRUE.equals(e.obligadoContabilidad()),
                            e.contribuyenteEspecial(), e.agenteRetencion(), e.contribuyenteRimpe())
                    .query(Long.class).single();
            auditoria.registrar(s, "EMISOR_CREADO", Map.of("emisorId", id, "ruc", e.ruc()));
            return obtener(jwt, id);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El RUC ya está registrado en este tenant");
        }
    }

    @GetMapping
    public List<Map<String, Object>> listar(@AuthenticationPrincipal Jwt jwt) {
        return db.sql("select " + COLUMNAS + " from emisor where tenant_id = ? order by id")
                .param(Sesion.de(jwt).tenant()).query().listOfRows();
    }

    @GetMapping("/{id}")
    public Map<String, Object> obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return db.sql("select " + COLUMNAS + " from emisor where id = ? and tenant_id = ?")
                .params(id, Sesion.de(jwt).tenant()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Emisor no encontrado"));
    }

    /** Sube el .p12: se valida que abra con la clave y que esté vigente, y se guarda cifrado. */
    @PutMapping(value = "/{id}/certificado", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> subirCertificado(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                @RequestParam("archivo") MultipartFile archivo,
                                                @RequestParam("clave") String clave) throws Exception {
        Sesion s = Sesion.de(jwt);
        obtener(jwt, id); // 404 si no es del tenant
        byte[] p12 = archivo.getBytes();
        Firmador f = new Firmador(p12, clave.toCharArray());
        var cert = f.certificado;
        if (cert.getNotAfter().toInstant().isBefore(Instant.now())) throw new IllegalArgumentException("El certificado está caducado");
        String huella = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        db.sql("""
                        update emisor set cert_p12 = ?, cert_clave = ?, cert_huella = ?, cert_sujeto = ?, cert_expira = ?
                        where id = ? and tenant_id = ?""")
                .params(cifrado.cifrar(p12), cifrado.cifrar(clave.getBytes(java.nio.charset.StandardCharsets.UTF_8)), huella,
                        cert.getSubjectX500Principal().getName(), Timestamp.from(cert.getNotAfter().toInstant()), id, s.tenant())
                .update();
        auditoria.registrar(s, "CERTIFICADO_SUBIDO", Map.of("emisorId", id, "huella", huella));
        return certificado(jwt, id);
    }

    @GetMapping("/{id}/certificado")
    public Map<String, Object> certificado(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        Map<String, Object> e = obtener(jwt, id);
        Map<String, Object> r = new LinkedHashMap<>();
        Timestamp expira = (Timestamp) e.get("certExpira");
        r.put("cargado", expira != null);
        r.put("sujeto", e.get("certSujeto"));
        r.put("expira", expira);
        if (expira != null) {
            long dias = ChronoUnit.DAYS.between(Instant.now(), expira.toInstant());
            r.put("vigente", dias >= 0);
            r.put("diasRestantes", dias);
        }
        return r;
    }

    /** Logo para el RIDE: PNG o JPEG de hasta 300 KB. */
    @PutMapping(value = "/{id}/logo", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> subirLogo(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                         @RequestParam("archivo") MultipartFile archivo) throws Exception {
        obtener(jwt, id);
        byte[] img = archivo.getBytes();
        if (img.length > 300 * 1024) throw new IllegalArgumentException("El logo no puede superar 300 KB");
        // Se valida por contenido, no por la extensión ni el Content-Type que manda el cliente.
        String tipo = img.length > 8 && (img[0] & 0xFF) == 0x89 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G' ? "image/png"
                : img.length > 3 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8 ? "image/jpeg" : null;
        if (tipo == null) throw new IllegalArgumentException("El logo debe ser PNG o JPEG");
        db.sql("update emisor set logo = ?, logo_tipo = ? where id = ? and tenant_id = ?")
                .params(img, tipo, id, Sesion.de(jwt).tenant()).update();
        auditoria.registrar(Sesion.de(jwt), "LOGO_SUBIDO", Map.of("emisorId", id));
        return Map.of("tipo", tipo, "bytes", img.length);
    }

    @PostMapping("/{id}/puntos-emision")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crearPunto(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Punto p) {
        obtener(jwt, id);
        try {
            long pid = db.sql("insert into punto_emision (emisor_id, estab, pto_emi, dir_establecimiento) values (?, ?, ?, ?) returning id")
                    .params(id, p.estab(), p.ptoEmi(), p.dirEstablecimiento()).query(Long.class).single();
            auditoria.registrar(Sesion.de(jwt), "PUNTO_EMISION_CREADO", Map.of("emisorId", id, "puntoId", pid));
            return Map.of("id", pid, "estab", p.estab(), "ptoEmi", p.ptoEmi(), "dirEstablecimiento", p.dirEstablecimiento());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese punto de emisión ya existe");
        }
    }

    @GetMapping("/{id}/puntos-emision")
    public List<Map<String, Object>> puntos(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        obtener(jwt, id);
        return db.sql("""
                        select p.id, p.estab, p.pto_emi as "ptoEmi", p.dir_establecimiento as "dirEstablecimiento", p.activo,
                               coalesce(jsonb_object_agg(s.cod_doc, s.ultimo) filter (where s.cod_doc is not null), '{}')::text as "ultimosSecuenciales"
                        from punto_emision p left join secuencial s on s.punto_emision_id = p.id
                        where p.emisor_id = ? group by p.id order by p.estab, p.pto_emi""")
                .param(id).query().listOfRows().stream().map(r -> {
                    var m = new LinkedHashMap<>(r);
                    m.put("ultimosSecuenciales", json.readTree((String) r.get("ultimosSecuenciales")));
                    return (Map<String, Object>) m;
                }).toList();
    }

    /** Cambios parciales: lo que no viene se mantiene; "" borra un campo opcional. El RUC no se cambia. */
    public record CambioEmisor(@Size(min = 1, max = 300) String razonSocial, String nombreComercial,
                               @Size(min = 1, max = 300) String dirMatriz, Boolean obligadoContabilidad,
                               String contribuyenteEspecial, String agenteRetencion, String contribuyenteRimpe, Boolean activo) {}

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> modificar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CambioEmisor e) {
        Sesion s = Sesion.de(jwt);
        obtener(jwt, id);
        db.sql("""
                        update emisor set razon_social = coalesce(?, razon_social),
                               nombre_comercial = nullif(coalesce(?, nombre_comercial), ''),
                               dir_matriz = coalesce(?, dir_matriz),
                               obligado_contabilidad = coalesce(?, obligado_contabilidad),
                               contribuyente_especial = nullif(coalesce(?, contribuyente_especial), ''),
                               agente_retencion = nullif(coalesce(?, agente_retencion), ''),
                               contribuyente_rimpe = nullif(coalesce(?, contribuyente_rimpe), ''),
                               activo = coalesce(?, activo)
                        where id = ? and tenant_id = ?""")
                .params(e.razonSocial(), e.nombreComercial(), e.dirMatriz(), e.obligadoContabilidad(), e.contribuyenteEspecial(),
                        e.agenteRetencion(), e.contribuyenteRimpe(), e.activo(), id, s.tenant())
                .update();
        auditoria.registrar(s, "EMISOR_MODIFICADO", Map.of("emisorId", id));
        return obtener(jwt, id);
    }

    public record CambioPunto(@Size(min = 1, max = 300) String dirEstablecimiento, Boolean activo) {}

    /** Un punto desactivado deja de emitir; sus secuenciales se conservan por si se reactiva. */
    @PatchMapping("/{id}/puntos-emision/{puntoId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> modificarPunto(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable long puntoId,
                                              @Valid @RequestBody CambioPunto p) {
        obtener(jwt, id);
        int n = db.sql("update punto_emision set dir_establecimiento = coalesce(?, dir_establecimiento), activo = coalesce(?, activo) where id = ? and emisor_id = ?")
                .params(p.dirEstablecimiento(), p.activo(), puntoId, id).update();
        if (n == 0) throw new NoSuchElementException("Punto de emisión no encontrado");
        auditoria.registrar(Sesion.de(jwt), "PUNTO_EMISION_MODIFICADO", Map.of("emisorId", id, "puntoId", puntoId));
        return puntos(jwt, id).stream().filter(x -> ((Number) x.get("id")).longValue() == puntoId).findFirst().orElseThrow();
    }
}
