package ec.facturacion.sri.comprobantes;

import ec.facturacion.sri.seguridad.Auditoria;
import ec.facturacion.sri.seguridad.Sesion;
import ec.facturacion.sri.correos.Correos;
import ec.facturacion.sri.webhooks.Webhooks;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.LongFunction;

@RestController
@RequestMapping("/sri")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class ComprobanteController {
    private static final String VISTA = """
            select c.clave_acceso as "claveAcceso", c.cod_doc as "codDoc", c.secuencial, c.estado,
                   c.fecha_emision::text as "fechaEmision", e.ruc, p.estab as "establecimiento", p.pto_emi as "puntoEmision",
                   c.numero_autorizacion as "numeroAutorizacion", c.fecha_autorizacion as "fechaAutorizacion",
                   c.mensajes::text as mensajes, c.intentos, c.creado_en as "creadoEn", c.actualizado_en as "actualizadoEn"
            from comprobante c join emisor e on e.id = c.emisor_id join punto_emision p on p.id = c.punto_emision_id
            """;

    private final EmisionService emision;
    private final JdbcClient db;
    private final JsonMapper json;

    private final Simplificado simple;
    private final Auditoria auditoria;
    private final Ride ride;
    private final Webhooks webhooks;
    private final Correos correos;
    private final TransactionTemplate tx;

    public ComprobanteController(EmisionService emision, JdbcClient db, JsonMapper json, Simplificado simple, Auditoria auditoria,
                                 Ride ride, Webhooks webhooks, Correos correos, TransactionTemplate tx) {
        this.emision = emision;
        this.correos = correos;
        this.webhooks = webhooks;
        this.tx = tx;
        this.ride = ride;
        this.auditoria = auditoria;
        this.db = db;
        this.json = json;
        this.simple = simple;
    }

    private static final String IDEM = "Idempotency-Key";

    /** Contenido con la estructura del XSD (para casos que los endpoints simplificados no cubren). */
    @PostMapping("/comprobantes")
    public ResponseEntity<Map<String, Object>> emitir(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                      @RequestBody EmisionService.Solicitud req) throws Exception {
        return emitir(jwt, idem, t -> req);
    }

    @PostMapping("/factura/emitir")
    public ResponseEntity<Map<String, Object>> factura(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                       @Valid @RequestBody Simplificado.Factura req) throws Exception {
        return emitir(jwt, idem, t -> simple.factura(t, req));
    }

    @PostMapping("/nota-credito/emitir")
    public ResponseEntity<Map<String, Object>> notaCredito(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                           @Valid @RequestBody Simplificado.NotaCredito req) throws Exception {
        return emitir(jwt, idem, t -> simple.notaCredito(t, req));
    }

    @PostMapping("/nota-debito/emitir")
    public ResponseEntity<Map<String, Object>> notaDebito(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                          @Valid @RequestBody Simplificado.NotaDebito req) throws Exception {
        return emitir(jwt, idem, t -> simple.notaDebito(t, req));
    }

    @PostMapping("/retencion/emitir")
    public ResponseEntity<Map<String, Object>> retencion(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                         @Valid @RequestBody Simplificado.ComprobanteRetencion req) throws Exception {
        return emitir(jwt, idem, t -> simple.retencion(t, req));
    }

    @PostMapping("/guia-remision/emitir")
    public ResponseEntity<Map<String, Object>> guia(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                    @Valid @RequestBody Simplificado.GuiaRemision req) throws Exception {
        return emitir(jwt, idem, t -> simple.guiaRemision(t, req));
    }

    @PostMapping("/liquidacion-compra/emitir")
    public ResponseEntity<Map<String, Object>> liquidacion(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = IDEM, required = false) String idem,
                                                           @Valid @RequestBody Simplificado.LiquidacionCompra req) throws Exception {
        return emitir(jwt, idem, t -> simple.liquidacionCompra(t, req));
    }

    /**
     * Emite y espera la respuesta del SRI unos segundos. Si el SRI no responde a tiempo el comprobante queda
     * guardado (PENDIENTE / EN_PROCESO) y se reintenta solo. Con Idempotency-Key, reenviar no duplica.
     */
    private ResponseEntity<Map<String, Object>> emitir(Jwt jwt, String idem, LongFunction<EmisionService.Solicitud> armar) throws Exception {
        Sesion s = Sesion.de(jwt);
        if (idem != null && (idem.isBlank() || idem.length() > 100)) throw new IllegalArgumentException("Idempotency-Key inválida");
        var previo = emision.porIdempotencia(s.tenant(), idem);
        if (previo.isPresent()) return ResponseEntity.ok(porId(previo.get()));
        long id;
        try {
            id = emision.registrar(s, armar.apply(s.tenant()), idem);
        } catch (DuplicateKeyException e) {
            // Dos peticiones con la misma Idempotency-Key a la vez: devuelve la que ganó.
            return ResponseEntity.ok(porId(emision.porIdempotencia(s.tenant(), idem).orElseThrow(() -> e)));
        }
        emision.procesar(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(porId(id));
    }

    @GetMapping("/comprobantes")
    public Map<String, Object> listar(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) String estado,
                                      @RequestParam(required = false) String codDoc,
                                      @RequestParam(required = false) String ruc,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                      @RequestParam(defaultValue = "0") int pagina,
                                      @RequestParam(defaultValue = "20") int tamano) {
        tamano = Math.clamp(tamano, 1, 100);
        pagina = Math.max(pagina, 0);
        StringBuilder where = new StringBuilder(" where c.tenant_id = ?");
        List<Object> params = new ArrayList<>(List.of(Sesion.de(jwt).tenant()));
        if (estado != null) { where.append(" and c.estado = ?"); params.add(estado); }
        if (codDoc != null) { where.append(" and c.cod_doc = ?"); params.add(codDoc); }
        if (ruc != null) { where.append(" and e.ruc = ?"); params.add(ruc); }
        if (desde != null) { where.append(" and c.fecha_emision >= ?"); params.add(desde); }
        if (hasta != null) { where.append(" and c.fecha_emision <= ?"); params.add(hasta); }

        long total = db.sql("select count(*) from comprobante c join emisor e on e.id = c.emisor_id" + where)
                .params(params).query(Long.class).single();
        params.add(tamano);
        params.add((long) pagina * tamano);
        var items = db.sql(VISTA + where + " order by c.creado_en desc limit ? offset ?").params(params).query().listOfRows()
                .stream().map(this::conMensajes).toList();
        return Map.of("items", items, "pagina", pagina, "tamano", tamano, "total", total);
    }

    @GetMapping("/comprobantes/{clave}")
    public Map<String, Object> obtener(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave) {
        return porClave(Sesion.de(jwt).tenant(), clave);
    }

    /** XML autorizado si existe; si no, el firmado. */
    @GetMapping(value = "/comprobantes/{clave}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> xml(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave) {
        String xml = db.sql("select coalesce(xml_autorizado, xml_firmado) from comprobante where tenant_id = ? and clave_acceso = ?")
                .params(Sesion.de(jwt).tenant(), clave).query(String.class).optional()
                .orElseThrow(() -> new NoSuchElementException("Comprobante no encontrado"));
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + clave + ".xml\"").body(xml);
    }

    /** RIDE en PDF (solo comprobantes autorizados; los anulados salen con marca de agua). */
    @GetMapping(value = "/comprobantes/{clave}/ride", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> ride(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave) throws Exception {
        byte[] pdf = ride.generar(Sesion.de(jwt).tenant(), clave);
        return ResponseEntity.ok().header("Content-Disposition", "inline; filename=\"" + clave + ".pdf\"").body(pdf);
    }

    /** Consulta al SRI ahora mismo (sin esperar al worker) y devuelve el estado actualizado. */
    @GetMapping("/verificar/{clave}")
    public Map<String, Object> verificar(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave) {
        long t = Sesion.de(jwt).tenant();
        emision.forzar(t, clave, false);
        return porClave(t, clave);
    }

    /** Para comprobantes en ERROR (agotaron reintentos): vuelve a intentar desde cero. */
    @PostMapping("/comprobantes/{clave}/reintentar")
    public Map<String, Object> reintentar(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave) {
        long t = Sesion.de(jwt).tenant();
        emision.forzar(t, clave, true);
        return porClave(t, clave);
    }

    public record Anulacion(@NotBlank @Size(max = 300) String motivo) {}

    /**
     * Marca como ANULADO un comprobante autorizado. El SRI no tiene servicio web de anulación:
     * además hay que anularlo en el portal "SRI en línea" (y el receptor debe aceptarlo).
     */
    @PatchMapping("/comprobantes/{clave}/anular")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> anular(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave, @Valid @RequestBody Anulacion req) {
        Sesion s = Sesion.de(jwt);
        porClave(s.tenant(), clave); // 404 si no existe o es de otro tenant
        Long id = tx.execute(st -> {
            Long anulado = db.sql("update comprobante set estado = 'ANULADO', actualizado_en = now() where tenant_id = ? and clave_acceso = ? and estado = 'AUTORIZADO' returning id")
                    .params(s.tenant(), clave).query(Long.class).optional().orElse(null);
            if (anulado != null) {
                webhooks.encolar(anulado);
                auditoria.registrar(s, "COMPROBANTE_ANULADO", Map.of("claveAcceso", clave, "motivo", req.motivo()));
            }
            return anulado;
        });
        if (id == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se puede anular un comprobante AUTORIZADO");
        webhooks.despacharPronto();
        return porClave(s.tenant(), clave);
    }

    public record Envio(List<String> destinatarios) {}

    /** Envía (o reenvía) el RIDE y el XML por email. Sin destinatarios usa el "Email" de infoAdicional. */
    @PostMapping("/comprobantes/{clave}/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> enviarEmail(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave,
                                           @RequestBody(required = false) Envio req) {
        Sesion s = Sesion.de(jwt);
        long id = correos.encolarManual(s.tenant(), clave, req == null ? null : req.destinatarios());
        auditoria.registrar(s, "EMAIL_ENCOLADO", Map.of("claveAcceso", clave, "correoId", id));
        return Map.of("id", id, "estado", "PENDIENTE");
    }

    @GetMapping("/comprobantes/{clave}/emails")
    public List<Map<String, Object>> emails(@AuthenticationPrincipal Jwt jwt, @PathVariable String clave) {
        return correos.historial(Sesion.de(jwt).tenant(), clave);
    }

    /** Procesa ya mismo todos los comprobantes pendientes del tenant, sin esperar al worker. */
    @PostMapping("/sincronizar")
    public Map<String, Object> sincronizar(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("procesados", emision.sincronizar(Sesion.de(jwt).tenant()));
    }

    private Map<String, Object> porClave(long tenant, String clave) {
        return db.sql(VISTA + " where c.tenant_id = ? and c.clave_acceso = ?").params(tenant, clave).query().listOfRows()
                .stream().findFirst().map(this::conMensajes)
                .orElseThrow(() -> new NoSuchElementException("Comprobante no encontrado"));
    }

    private Map<String, Object> porId(long id) {
        var r = conMensajes(db.sql(VISTA + " where c.id = ?").param(id).query().singleRow());
        r.put("xmlAutorizado", db.sql("select xml_autorizado from comprobante where id = ?").param(id).query(String.class).optional().orElse(null));
        return r;
    }

    private Map<String, Object> conMensajes(Map<String, Object> fila) {
        var r = new LinkedHashMap<>(fila);
        r.put("mensajes", json.readTree((String) fila.get("mensajes")));
        return r;
    }
}
