package ec.facturacion.sri.webhooks;

import ec.facturacion.sri.seguridad.Auditoria;
import ec.facturacion.sri.seguridad.Cifrado;
import ec.facturacion.sri.seguridad.Sesion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/webhooks")
@PreAuthorize("hasRole('ADMIN')")
public class WebhookController {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final Webhooks webhooks;
    private final Cifrado cifrado;
    private final Auditoria auditoria;

    public WebhookController(JdbcClient db, Webhooks webhooks, Cifrado cifrado, Auditoria auditoria) {
        this.db = db;
        this.webhooks = webhooks;
        this.cifrado = cifrado;
        this.auditoria = auditoria;
    }

    public record Nuevo(@NotBlank @Size(max = 2000) String url, List<String> eventos) {}

    /** El secreto para verificar la firma se devuelve solo aquí; después no se puede volver a consultar. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crear(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Nuevo req) {
        Sesion s = Sesion.de(jwt);
        webhooks.validarUrl(req.url());
        List<String> eventos = req.eventos() == null || req.eventos().isEmpty() ? Webhooks.EVENTOS : req.eventos();
        for (String e : eventos)
            if (!Webhooks.EVENTOS.contains(e)) throw new IllegalArgumentException("Evento inválido: " + e + ". Válidos: " + Webhooks.EVENTOS);
        long cuantos = db.sql("select count(*) from webhook where tenant_id = ?").param(s.tenant()).query(Long.class).single();
        if (cuantos >= 10) throw new ResponseStatusException(HttpStatus.CONFLICT, "Máximo 10 webhooks por tenant");

        String secreto = nuevoSecreto();
        long id = db.sql("insert into webhook (tenant_id, url, secreto, eventos) values (?, ?, ?, ?) returning id")
                .params(s.tenant(), req.url(), cifrado.cifrar(secreto.getBytes(StandardCharsets.UTF_8)), eventos.toArray(String[]::new))
                .query(Long.class).single();
        auditoria.registrar(s, "WEBHOOK_CREADO", Map.of("webhookId", id, "url", req.url()));
        return Map.of("id", id, "url", req.url(), "eventos", eventos, "secreto", secreto);
    }

    @GetMapping
    public List<Map<String, Object>> listar(@AuthenticationPrincipal Jwt jwt) {
        return db.sql("select id, url, array_to_string(eventos, ',') as eventos, activo, creado_en as \"creadoEn\" from webhook where tenant_id = ? order by id")
                .param(Sesion.de(jwt).tenant()).query().listOfRows().stream().map(r -> {
                    var m = new LinkedHashMap<>(r);
                    m.put("eventos", List.of(((String) r.get("eventos")).split(",")));
                    return (Map<String, Object>) m;
                }).toList();
    }

    public record Cambio(@Size(max = 2000) String url, List<String> eventos, Boolean activo) {}

    /** Pausar (activo=false), reanudar, o cambiar URL/eventos. Lo pendiente de un webhook pausado se entrega al reanudarlo. */
    @PatchMapping("/{id}")
    public Map<String, Object> modificar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody Cambio req) {
        Sesion s = Sesion.de(jwt);
        if (req.url() != null) webhooks.validarUrl(req.url());
        if (req.eventos() != null) {
            if (req.eventos().isEmpty()) throw new IllegalArgumentException("eventos no puede quedar vacío");
            for (String e : req.eventos())
                if (!Webhooks.EVENTOS.contains(e)) throw new IllegalArgumentException("Evento inválido: " + e + ". Válidos: " + Webhooks.EVENTOS);
        }
        int n = db.sql("update webhook set url = coalesce(?, url), eventos = coalesce(?, eventos), activo = coalesce(?, activo) where id = ? and tenant_id = ?")
                .params(req.url(), req.eventos() == null ? null : req.eventos().toArray(String[]::new), req.activo(), id, s.tenant()).update();
        if (n == 0) throw new NoSuchElementException("Webhook no encontrado");
        auditoria.registrar(s, "WEBHOOK_MODIFICADO", Map.of("webhookId", id, "activo", String.valueOf(req.activo())));
        if (Boolean.TRUE.equals(req.activo())) webhooks.despacharPronto();
        return listar(jwt).stream().filter(w -> ((Number) w.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    /** Nuevo secreto (por ejemplo, si el anterior se filtró). Se muestra solo en esta respuesta; el anterior deja de valer. */
    @PostMapping("/{id}/rotar-secreto")
    public Map<String, Object> rotarSecreto(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        Sesion s = Sesion.de(jwt);
        String secreto = nuevoSecreto();
        int n = db.sql("update webhook set secreto = ? where id = ? and tenant_id = ?")
                .params(cifrado.cifrar(secreto.getBytes(StandardCharsets.UTF_8)), id, s.tenant()).update();
        if (n == 0) throw new NoSuchElementException("Webhook no encontrado");
        auditoria.registrar(s, "WEBHOOK_SECRETO_ROTADO", Map.of("webhookId", id));
        return Map.of("id", id, "secreto", secreto);
    }

    private static String nuevoSecreto() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        Sesion s = Sesion.de(jwt);
        if (db.sql("delete from webhook where id = ? and tenant_id = ?").params(id, s.tenant()).update() == 0)
            throw new NoSuchElementException("Webhook no encontrado");
        auditoria.registrar(s, "WEBHOOK_ELIMINADO", Map.of("webhookId", id));
    }

    /** Historial de notificaciones: filtros estado (PENDIENTE, ENTREGADO, FALLIDO) y claveAcceso. */
    @GetMapping("/logs")
    public Map<String, Object> logs(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(required = false) String estado,
                                    @RequestParam(required = false) String claveAcceso,
                                    @RequestParam(defaultValue = "0") int pagina,
                                    @RequestParam(defaultValue = "20") int tamano) {
        tamano = Math.clamp(tamano, 1, 100);
        pagina = Math.max(pagina, 0);
        StringBuilder where = new StringBuilder(" where we.tenant_id = ?");
        List<Object> params = new ArrayList<>(List.of(Sesion.de(jwt).tenant()));
        if (estado != null) { where.append(" and we.estado = ?"); params.add(estado); }
        if (claveAcceso != null) { where.append(" and we.clave_acceso = ?"); params.add(claveAcceso); }
        long total = db.sql("select count(*) from webhook_entrega we" + where).params(params).query(Long.class).single();
        params.add(tamano);
        params.add((long) pagina * tamano);
        var items = db.sql("""
                        select we.id, we.webhook_id as "webhookId", w.url, we.evento, we.clave_acceso as "claveAcceso", we.estado,
                               we.intentos, we.respuesta_status as "respuestaStatus", we.respuesta,
                               we.creado_en as "creadoEn", we.entregado_en as "entregadoEn", we.proximo_intento as "proximoIntento"
                        from webhook_entrega we join webhook w on w.id = we.webhook_id""" + where + " order by we.id desc limit ? offset ?")
                .params(params).query().listOfRows();
        return Map.of("items", items, "pagina", pagina, "tamano", tamano, "total", total);
    }

    /** Vuelve a encolar una entrega (por ejemplo, una FALLIDA después de arreglar el servidor receptor). */
    @PostMapping("/logs/{id}/reenviar")
    public Map<String, Object> reenviar(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        int n = db.sql("update webhook_entrega set estado = 'PENDIENTE', intentos = 0, proximo_intento = now() where id = ? and tenant_id = ?")
                .params(id, Sesion.de(jwt).tenant()).update();
        if (n == 0) throw new NoSuchElementException("Entrega no encontrada");
        webhooks.despacharPronto();
        return Map.of("id", id, "estado", "PENDIENTE");
    }
}
