package ec.facturacion.sri.correos;

import ec.facturacion.sri.comprobantes.Ride;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Envía al receptor el RIDE (PDF) y el XML autorizado. Cola en Postgres con reintentos, igual que los webhooks.
 * Solo funciona con SMTP configurado (SMTP_HOST); sin él, no encola nada.
 */
@Service
public class Correos {
    private static final Logger log = LoggerFactory.getLogger(Correos.class);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Set<String> CAMPOS_EMAIL = Set.of("email", "e-mail", "correo", "correo electronico", "correo electrónico", "mail");
    private static final Map<String, String> TIPOS = Map.of("01", "Factura", "03", "Liquidación de compra",
            "04", "Nota de crédito", "05", "Nota de débito", "06", "Guía de remisión", "07", "Comprobante de retención");
    private static final int MAX_DESTINATARIOS = 5, MAX_INTENTOS = 8;

    private final JdbcClient db;
    private final JavaMailSender smtp;
    private final Ride ride;
    private final boolean habilitado;
    private final String remitente;
    private final AtomicBoolean enviando = new AtomicBoolean();

    public Correos(JdbcClient db, JavaMailSender smtp, Ride ride,
                   @Value("${app.correo.smtp-host:}") String host, @Value("${app.correo.remitente:}") String remitente) {
        this.db = db;
        this.smtp = smtp;
        this.ride = ride;
        this.habilitado = !host.isBlank();
        this.remitente = remitente;
        if (habilitado && remitente.isBlank()) throw new IllegalStateException("Con SMTP_HOST hay que definir SMTP_FROM (remitente)");
    }

    public boolean habilitado() {
        return habilitado;
    }

    /**
     * Encola el envío si el comprobante tiene emails en infoAdicional ("Email", "Correo"...).
     * Se llama dentro de la transacción que lo marca AUTORIZADO.
     */
    public void encolarAutomatico(long comprobanteId) {
        if (!habilitado) return;
        record Fila(long tenantId, String xmlFirmado) {}
        Fila f = db.sql("select tenant_id, xml_firmado from comprobante where id = ? and estado = 'AUTORIZADO'")
                .param(comprobanteId).query(Fila.class).optional().orElse(null);
        if (f == null) return;
        List<String> emails = emailsDelXml(f.xmlFirmado());
        if (!emails.isEmpty()) insertar(f.tenantId(), comprobanteId, emails);
    }

    /** Reenvío manual; sin destinatarios usa los del comprobante. */
    public long encolarManual(long tenant, String clave, List<String> destinatarios) {
        if (!habilitado) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El envío de emails no está configurado (SMTP_HOST)");
        record Fila(long id, String estado, String xmlFirmado) {}
        Fila f = db.sql("select id, estado, xml_firmado from comprobante where tenant_id = ? and clave_acceso = ?")
                .params(tenant, clave).query(Fila.class).optional()
                .orElseThrow(() -> new java.util.NoSuchElementException("Comprobante no encontrado"));
        if (!f.estado().equals("AUTORIZADO")) throw new IllegalArgumentException("Solo se envían comprobantes AUTORIZADOS");
        List<String> emails = destinatarios == null || destinatarios.isEmpty() ? emailsDelXml(f.xmlFirmado()) : validar(destinatarios);
        if (emails.isEmpty()) throw new IllegalArgumentException("El comprobante no tiene email en infoAdicional; indica destinatarios");
        long id = insertar(tenant, f.id(), emails);
        despacharPronto();
        return id;
    }

    private long insertar(long tenant, long comprobanteId, List<String> emails) {
        return db.sql("insert into correo (tenant_id, comprobante_id, destinatarios) values (?, ?, ?) returning id")
                .params(tenant, comprobanteId, String.join(",", emails)).query(Long.class).single();
    }

    static List<String> emailsDelXml(String xml) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var campos = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagName("campoAdicional");
            List<String> todos = new ArrayList<>();
            for (int i = 0; i < campos.getLength(); i++) {
                Element c = (Element) campos.item(i);
                if (CAMPOS_EMAIL.contains(c.getAttribute("nombre").trim().toLowerCase()))
                    for (String e : c.getTextContent().split("[,;\\s]+")) if (!e.isBlank()) todos.add(e.trim());
            }
            // Desde el XML se descartan los inválidos en vez de fallar: el comprobante ya está autorizado.
            return todos.stream().filter(e -> EMAIL.matcher(e).matches()).distinct().limit(MAX_DESTINATARIOS).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    static List<String> validar(List<String> emails) {
        var unicos = new LinkedHashSet<String>();
        for (String e : emails) {
            String x = e == null ? "" : e.trim();
            if (!EMAIL.matcher(x).matches()) throw new IllegalArgumentException("Email inválido: " + e);
            unicos.add(x);
        }
        if (unicos.size() > MAX_DESTINATARIOS) throw new IllegalArgumentException("Máximo " + MAX_DESTINATARIOS + " destinatarios");
        return List.copyOf(unicos);
    }

    public void despacharPronto() {
        if (habilitado) Thread.startVirtualThread(this::despachar);
    }

    @Scheduled(fixedDelayString = "${app.correo.intervalo-ms:10000}")
    public void despachar() {
        if (!habilitado || !enviando.compareAndSet(false, true)) return;
        try {
            record Envio(long id, long tenantId, String destinatarios, int intentos, String claveAcceso, String codDoc,
                         String numero, String xml, String razonSocial) {}
            List<Envio> lote = db.sql("""
                            with lote as (
                                select id from correo where estado = 'PENDIENTE' and proximo_intento <= now()
                                order by proximo_intento limit 20 for update skip locked)
                            update correo co set proximo_intento = now() + interval '5 minutes'
                            from lote, comprobante c, emisor e, punto_emision p
                            where co.id = lote.id and c.id = co.comprobante_id and e.id = c.emisor_id and p.id = c.punto_emision_id
                            returning co.id, co.tenant_id, co.destinatarios, co.intentos, c.clave_acceso, c.cod_doc,
                                      p.estab || '-' || p.pto_emi || '-' || c.secuencial as numero,
                                      coalesce(c.xml_autorizado, c.xml_firmado) as xml, e.razon_social""")
                    .query(Envio.class).list();
            if (lote.isEmpty()) return;
            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Envio e : lote) ex.submit(() -> {
                    String error = null;
                    try {
                        String tipo = TIPOS.getOrDefault(e.codDoc(), "Comprobante");
                        var msg = smtp.createMimeMessage();
                        var h = new MimeMessageHelper(msg, true, "UTF-8");
                        h.setFrom(new InternetAddress(remitente, e.razonSocial(), "UTF-8"));
                        h.setTo(e.destinatarios().split(","));
                        h.setSubject(tipo + " " + e.numero() + " - " + e.razonSocial());
                        h.setText("Estimado cliente:\n\nAdjuntamos su " + tipo.toLowerCase() + " electrónica " + e.numero()
                                + " emitida por " + e.razonSocial().replaceAll("\\.$", "") + ".\n\nClave de acceso: " + e.claveAcceso()
                                + "\n\nPuede verificarla en el portal del SRI.\n\nEste es un mensaje automático, por favor no responda.");
                        h.addAttachment(e.claveAcceso() + ".pdf", new ByteArrayResource(ride.generar(e.tenantId(), e.claveAcceso())), "application/pdf");
                        h.addAttachment(e.claveAcceso() + ".xml", new ByteArrayResource(e.xml().getBytes(StandardCharsets.UTF_8)), "application/xml");
                        smtp.send(msg);
                    } catch (Exception ex2) {
                        error = ex2.getClass().getSimpleName() + ": " + ex2.getMessage();
                        log.warn("Email del comprobante {} falló: {}", e.claveAcceso(), error);
                    }
                    int n = e.intentos() + 1;
                    long espera = Math.min(60L << Math.min(n, 20), 6 * 3600); // 2 min, 4 min... tope 6 horas
                    db.sql("""
                                    update correo set intentos = ?, error = ?,
                                           estado = case when ? then 'ENVIADO' when ? >= ? then 'FALLIDO' else 'PENDIENTE' end,
                                           enviado_en = case when ? then now() end, proximo_intento = now() + make_interval(secs => ?)
                                    where id = ?""")
                            .params(n, error == null ? null : error.substring(0, Math.min(500, error.length())),
                                    error == null, n, MAX_INTENTOS, error == null, espera, e.id())
                            .update();
                });
            }
        } finally {
            enviando.set(false);
        }
    }

    public List<Map<String, Object>> historial(long tenant, String clave) {
        return db.sql("""
                        select co.id, co.destinatarios, co.estado, co.intentos, co.error, co.creado_en as "creadoEn", co.enviado_en as "enviadoEn"
                        from correo co join comprobante c on c.id = co.comprobante_id
                        where c.tenant_id = ? and c.clave_acceso = ? order by co.id desc""")
                .params(tenant, clave).query().listOfRows().stream().map(r -> {
                    var m = new java.util.LinkedHashMap<>(r);
                    m.put("destinatarios", List.of(((String) r.get("destinatarios")).split(",")));
                    return (Map<String, Object>) m;
                }).toList();
    }
}
