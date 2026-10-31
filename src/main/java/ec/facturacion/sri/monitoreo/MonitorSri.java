package ec.facturacion.sri.monitoreo;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Salud de la conexión con el SRI y de las colas. Publica métricas (Prometheus) y avisa por email
 * a ALERTAS_EMAIL cuando el SRI deja de responder, cuando vuelve, y cuando hay comprobantes atascados.
 */
@Component
public class MonitorSri {
    private static final Logger log = LoggerFactory.getLogger(MonitorSri.class);
    static final int UMBRAL_FALLOS = 5;
    private static final Duration ATASCO = Duration.ofMinutes(30), REPETIR_AVISO_ATASCO = Duration.ofHours(1);

    private final JdbcClient db;
    private final JavaMailSender smtp;
    private final String[] destinatarios;
    private final String remitente;
    private final boolean smtpHabilitado;

    private final AtomicInteger fallosSeguidos = new AtomicInteger();
    private volatile boolean caido;
    private volatile Instant ultimoAvisoAtasco = Instant.EPOCH;

    public MonitorSri(JdbcClient db, JavaMailSender smtp, MeterRegistry registry,
                      @Value("${app.alertas.emails:}") String emails,
                      @Value("${app.correo.remitente:}") String remitente,
                      @Value("${app.correo.smtp-host:}") String smtpHost) {
        this.db = db;
        this.smtp = smtp;
        this.destinatarios = Arrays.stream(emails.split("[,;\\s]+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        this.remitente = remitente;
        this.smtpHabilitado = !smtpHost.isBlank();

        Gauge.builder("sri.disponible", () -> caido ? 0 : 1).description("1 si el SRI responde, 0 si está caído").register(registry);
        Gauge.builder("sri.comprobantes.pendientes", () -> contar("select count(*) from comprobante where estado in ('PENDIENTE', 'EN_PROCESO')"))
                .description("Comprobantes esperando al SRI").register(registry);
        Gauge.builder("sri.comprobantes.pendiente.antiguedad.segundos", () -> contar("""
                        select coalesce(extract(epoch from now() - min(creado_en)), 0)::bigint
                        from comprobante where estado in ('PENDIENTE', 'EN_PROCESO')"""))
                .description("Edad del comprobante pendiente más antiguo").baseUnit("seconds").register(registry);
        Gauge.builder("sri.comprobantes.error", () -> contar("select count(*) from comprobante where estado = 'ERROR'"))
                .description("Comprobantes que agotaron los reintentos").register(registry);
        Gauge.builder("sri.webhooks.pendientes", () -> contar("select count(*) from webhook_entrega where estado = 'PENDIENTE'")).register(registry);
        Gauge.builder("sri.correos.pendientes", () -> contar("select count(*) from correo where estado = 'PENDIENTE'")).register(registry);
    }

    private double contar(String sql) {
        try {
            return db.sql(sql).query(Long.class).single();
        } catch (Exception e) {
            return Double.NaN; // la base no responde: que se vea en la métrica, sin romper el scrape
        }
    }

    public boolean caido() {
        return caido;
    }

    /** Una llamada al SRI respondió. */
    public void exito() {
        fallosSeguidos.set(0);
        if (caido) {
            caido = false;
            alertar("SRI recuperado", "El SRI volvió a responder. Los comprobantes pendientes se están enviando solos.");
        }
    }

    /** Una llamada al SRI falló por red o timeout (no cuenta un rechazo del SRI: eso es una respuesta). */
    public void fallo(String error) {
        if (fallosSeguidos.incrementAndGet() >= UMBRAL_FALLOS && !caido) {
            caido = true;
            alertar("SRI no responde", "Las últimas " + UMBRAL_FALLOS + " llamadas al SRI fallaron. Último error: " + error
                    + "\n\nLos comprobantes quedan guardados y se reintentan solos. Recibirás otro aviso cuando el SRI vuelva.");
        }
    }

    @Scheduled(fixedDelayString = "${app.alertas.intervalo-ms:300000}")
    public void revisarAtascos() {
        long atascados = (long) contar("select count(*) from comprobante where estado in ('PENDIENTE', 'EN_PROCESO') and creado_en < now() - interval '"
                + ATASCO.toMinutes() + " minutes'");
        long errores = (long) contar("select count(*) from comprobante where estado = 'ERROR' and actualizado_en > now() - interval '1 hour'");
        if ((atascados > 0 || errores > 0) && Instant.now().isAfter(ultimoAvisoAtasco.plus(REPETIR_AVISO_ATASCO))) {
            ultimoAvisoAtasco = Instant.now();
            alertar("Comprobantes atascados", atascados + " comprobante(s) llevan más de " + ATASCO.toMinutes()
                    + " minutos sin autorizarse y " + errores + " pasaron a ERROR en la última hora."
                    + "\nRevísalos con GET /sri/comprobantes?estado=ERROR o POST /sri/sincronizar.");
        }
    }

    void alertar(String asunto, String cuerpo) {
        log.warn("ALERTA {}: {}", asunto, cuerpo);
        if (!smtpHabilitado || destinatarios.length == 0) return;
        Thread.startVirtualThread(() -> {
            try {
                var m = new SimpleMailMessage();
                m.setFrom(remitente);
                m.setTo(destinatarios);
                m.setSubject("[Servicio SRI] " + asunto);
                m.setText(cuerpo);
                smtp.send(m);
            } catch (Exception e) {
                log.error("No se pudo enviar la alerta por email: {}", e.toString());
            }
        });
    }
}
