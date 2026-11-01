package ec.facturacion.sri.webhooks;

import ec.facturacion.sri.seguridad.Cifrado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Notifica a los sistemas de los clientes cuando un comprobante llega a un estado final.
 * Cola en Postgres (webhook_entrega) con reintentos; cada envío va firmado con HMAC-SHA256.
 */
@Service
public class Webhooks {
    private static final Logger log = LoggerFactory.getLogger(Webhooks.class);
    public static final List<String> EVENTOS = List.of("comprobante.autorizado", "comprobante.no_autorizado",
            "comprobante.devuelta", "comprobante.error", "comprobante.anulado");
    private static final int MAX_INTENTOS = 10;

    private final JdbcClient db;
    private final Cifrado cifrado;
    private final boolean permitirRedPrivada, permitirHttp;
    // Sin redirecciones: un 302 hacia la red interna saltaría la validación anti-SSRF.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicBoolean despachando = new AtomicBoolean();

    public Webhooks(JdbcClient db, Cifrado cifrado,
                    @Value("${app.webhooks.permitir-red-privada:false}") boolean permitirRedPrivada,
                    @Value("${app.webhooks.permitir-http:false}") boolean permitirHttp) {
        this.db = db;
        this.cifrado = cifrado;
        this.permitirRedPrivada = permitirRedPrivada;
        this.permitirHttp = permitirHttp;
    }

    /**
     * Encola el evento del estado actual del comprobante para cada webhook suscrito.
     * Se llama dentro de la misma transacción que cambia el estado: o quedan ambos o ninguno.
     */
    public void encolar(long comprobanteId) {
        db.sql("""
                        insert into webhook_entrega (webhook_id, tenant_id, evento, clave_acceso, payload)
                        select w.id, c.tenant_id, 'comprobante.' || lower(c.estado), c.clave_acceso,
                               jsonb_build_object('evento', 'comprobante.' || lower(c.estado), 'claveAcceso', c.clave_acceso,
                                   'estado', c.estado, 'codDoc', c.cod_doc, 'secuencial', c.secuencial, 'ruc', e.ruc,
                                   'numeroAutorizacion', c.numero_autorizacion, 'fechaAutorizacion', c.fecha_autorizacion,
                                   'mensajes', c.mensajes, 'idempotencyKey', c.idempotency_key,
                                   'subtotal', c.subtotal, 'iva', c.iva, 'total', c.importe_total,
                                   'establecimiento', p.estab, 'puntoEmision', p.pto_emi, 'fechaEmision', c.fecha_emision, 'fecha', now())
                        from comprobante c join emisor e on e.id = c.emisor_id join punto_emision p on p.id = c.punto_emision_id
                        join webhook w on w.tenant_id = c.tenant_id -- también pausados: se entregan al reanudarlos
                        where c.id = ? and ('comprobante.' || lower(c.estado)) = any (w.eventos)""")
                .param(comprobanteId).update();
    }

    /** Despacha ya mismo, sin esperar al worker (en un hilo virtual para no frenar la respuesta). */
    public void despacharPronto() {
        Thread.startVirtualThread(this::despachar);
    }

    @Scheduled(fixedDelayString = "${app.webhooks.intervalo-ms:5000}")
    public void despachar() {
        if (!despachando.compareAndSet(false, true)) return; // ya hay un despacho en curso en esta instancia
        try {
            record Entrega(long id, String evento, String payload, int intentos, String url, byte[] secreto) {}
            // Reclama hasta 50 entregas: proximo_intento se corre 2 minutos, así otra réplica no las toma a la vez.
            List<Entrega> lote = db.sql("""
                            with lote as (
                                select id from webhook_entrega where estado = 'PENDIENTE' and proximo_intento <= now()
                                  and webhook_id in (select id from webhook where activo) -- pausado: espera a que se reactive
                                order by proximo_intento limit 50 for update skip locked)
                            update webhook_entrega we set proximo_intento = now() + interval '2 minutes'
                            from lote, webhook w where we.id = lote.id and w.id = we.webhook_id
                            returning we.id, we.evento, we.payload::text as payload, we.intentos, w.url, w.secreto""")
                    .query(Entrega.class).list();
            if (lote.isEmpty()) return;
            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Entrega e : lote) ex.submit(() -> enviar(e.id(), e.evento(), e.payload(), e.intentos(), e.url(), e.secreto()));
            }
        } finally {
            despachando.set(false);
        }
    }

    private void enviar(long id, String evento, String payload, int intentos, String url, byte[] secretoCifrado) {
        Integer status = null;
        String respuesta;
        try {
            validarUrl(url); // de nuevo al enviar: el DNS pudo cambiar desde el registro
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String firma = hmac(cifrado.descifrar(secretoCifrado), ts + "." + payload);
            var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "sri-service-webhooks")
                    .header("X-SRI-Evento", evento)
                    .header("X-SRI-Entrega", String.valueOf(id))
                    .header("X-SRI-Timestamp", ts)
                    .header("X-SRI-Firma", "sha256=" + firma)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            status = res.statusCode();
            respuesta = res.body();
        } catch (Exception ex) {
            respuesta = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        if (respuesta != null && respuesta.length() > 500) respuesta = respuesta.substring(0, 500);
        boolean ok = status != null && status >= 200 && status < 300;
        int n = intentos + 1;
        long espera = Math.min(30L << Math.min(n, 20), 3600); // 1 min, 2 min, 4 min... tope 1 hora
        db.sql("""
                        update webhook_entrega set intentos = ?, respuesta_status = ?, respuesta = ?,
                               estado = case when ? then 'ENTREGADO' when ? >= ? then 'FALLIDO' else 'PENDIENTE' end,
                               entregado_en = case when ? then now() else null end,
                               proximo_intento = now() + make_interval(secs => ?)
                        where id = ?""")
                .params(n, status, respuesta, ok, n, MAX_INTENTOS, ok, espera, id).update();
        if (!ok) log.info("Webhook {} entrega {} falló (intento {}): {}", url, id, n, status != null ? "HTTP " + status : respuesta);
    }

    static String hmac(byte[] secreto, String datos) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Anti-SSRF: solo HTTPS hacia direcciones públicas (nada de localhost, red interna ni metadata de la nube). */
    public void validarUrl(String url) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL inválida");
        }
        String esquema = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!esquema.equals("https") && !(permitirHttp && esquema.equals("http")))
            throw new IllegalArgumentException("La URL del webhook debe usar https");
        if (u.getHost() == null || u.getUserInfo() != null) throw new IllegalArgumentException("URL inválida");
        if (permitirRedPrivada) return;
        try {
            for (InetAddress a : InetAddress.getAllByName(u.getHost()))
                if (esPrivada(a)) throw new IllegalArgumentException("La URL del webhook apunta a una red privada");
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("No se pudo resolver el host del webhook");
        }
    }

    static boolean esPrivada(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress())
            return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) return true; // fc00::/7 (ULA)
            // ::ffff:a.b.c.d (IPv4 mapeada) se evalúa como IPv4
            boolean mapeada = true;
            for (int i = 0; i < 10; i++) mapeada &= b[i] == 0;
            if (mapeada && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return esPrivada(InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, 12, 16)));
                } catch (Exception e) {
                    return true;
                }
            }
            return false;
        }
        int x = b[0] & 0xFF, y = b[1] & 0xFF;
        return x == 0 || (x == 100 && y >= 64 && y <= 127)  // 100.64.0.0/10 (CGNAT)
                || (x == 198 && (y == 18 || y == 19))           // 198.18.0.0/15 (benchmark)
                || x >= 224;                                    // multicast y reservadas
    }
}
