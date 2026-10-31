package ec.facturacion.sri.comprobantes;

import ec.facturacion.sri.seguridad.Auditoria;
import ec.facturacion.sri.seguridad.Cifrado;
import ec.facturacion.sri.seguridad.Sesion;
import ec.facturacion.sri.sri.ClaveAcceso;
import ec.facturacion.sri.sri.Firmador;
import ec.facturacion.sri.sri.SriClient;
import ec.facturacion.sri.sri.XmlComprobante;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ec.facturacion.sri.correos.Correos;
import ec.facturacion.sri.monitoreo.MonitorSri;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import ec.facturacion.sri.webhooks.Webhooks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Ciclo de vida de un comprobante:
 * PENDIENTE (firmado y guardado) -> EN_PROCESO (recibido por el SRI) -> AUTORIZADO | NO_AUTORIZADO,
 * o DEVUELTA si el SRI rechaza el XML. Los fallos de red se reintentan con backoff hasta pasar a ERROR.
 */
@Service
public class EmisionService {
    private static final Logger log = LoggerFactory.getLogger(EmisionService.class);
    private static final int MAX_INTENTOS = 15;
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcClient db;
    private final JsonMapper json;
    private final Cifrado cifrado;
    private final SriClient sri;
    private final Auditoria auditoria;
    private final String ambiente;
    private final Webhooks webhooks;
    private final Correos correos;
    private final MonitorSri monitor;
    private final MeterRegistry registry;
    private final TransactionTemplate tx;
    // Clave: emisorId:huella del certificado, así un cambio de .p12 invalida la caché en todas las réplicas.
    private final Map<String, Firmador> firmadores = new ConcurrentHashMap<>();

    public EmisionService(JdbcClient db, JsonMapper json, Cifrado cifrado, SriClient sri, Auditoria auditoria,
                          @Value("${app.sri-ambiente}") String ambiente, Webhooks webhooks, Correos correos, MonitorSri monitor, MeterRegistry registry,
                          TransactionTemplate tx) {
        this.monitor = monitor;
        this.registry = registry;
        this.ambiente = ambiente;
        this.webhooks = webhooks;
        this.correos = correos;
        this.tx = tx;
        this.db = db;
        this.json = json;
        this.cifrado = cifrado;
        this.sri = sri;
        this.auditoria = auditoria;
    }

    public record Solicitud(String ruc, String establecimiento, String puntoEmision, String tipo, String version, JsonNode contenido) {}

    record Emisor(long id, String ruc, String razonSocial, String nombreComercial, String dirMatriz, String agenteRetencion,
                  String contribuyenteRimpe, String certHuella, OffsetDateTime certExpira, long puntoId) {}

    public Optional<Long> porIdempotencia(long tenant, String key) {
        if (key == null) return Optional.empty();
        return db.sql("select id from comprobante where tenant_id = ? and idempotency_key = ?").params(tenant, key).query(Long.class).optional();
    }

    /** Firma y guarda el comprobante en una sola transacción con su secuencial. Aún no habla con el SRI. */
    @Transactional
    public long registrar(Sesion s, Solicitud req, String idempotencyKey) throws Exception {
        long tenant = s.tenant();
        if (req.contenido() == null || !req.contenido().isObject()) throw new IllegalArgumentException("Falta contenido");
        XmlComprobante.Tipo tipo = XmlComprobante.tipo(req.tipo());
        String fecha = XmlComprobante.fecha(req.tipo(), req.contenido());

        Emisor e = db.sql("""
                        select e.id, e.ruc, e.razon_social, e.nombre_comercial, e.dir_matriz, e.agente_retencion,
                               e.contribuyente_rimpe, e.cert_huella, e.cert_expira, p.id as punto_id
                        from emisor e join punto_emision p on p.emisor_id = e.id
                        where e.tenant_id = ? and e.ruc = ? and p.estab = ? and p.pto_emi = ? and p.activo""")
                .params(tenant, req.ruc(), req.establecimiento(), req.puntoEmision()).query(Emisor.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Emisor o punto de emisión no registrado: " + req.ruc() + " " + req.establecimiento() + "-" + req.puntoEmision()));
        if (e.certHuella() == null) throw new IllegalArgumentException("El emisor no tiene certificado cargado");
        if (e.certExpira().isBefore(OffsetDateTime.now())) throw new IllegalArgumentException("El certificado del emisor está caducado");

        // Atómico: bloquea la fila del secuencial hasta el commit, así dos emisiones nunca comparten número.
        long numero = db.sql("""
                        insert into secuencial (punto_emision_id, cod_doc, ultimo) values (?, ?, 1)
                        on conflict (punto_emision_id, cod_doc) do update set ultimo = secuencial.ultimo + 1
                        returning ultimo""")
                .params(e.puntoId(), tipo.codDoc()).query(Long.class).single();
        if (numero > 999_999_999) throw new IllegalStateException("Secuencial agotado para este punto de emisión");
        String secuencial = String.format("%09d", numero);
        String clave = ClaveAcceso.generar(fecha, tipo.codDoc(), e.ruc(), ambiente, req.establecimiento(), req.puntoEmision(), secuencial);

        ObjectNode it = json.createObjectNode()
                .put("ambiente", ambiente).put("tipoEmision", "1").put("razonSocial", e.razonSocial());
        if (e.nombreComercial() != null) it.put("nombreComercial", e.nombreComercial());
        it.put("ruc", e.ruc()).put("claveAcceso", clave).put("codDoc", tipo.codDoc())
                .put("estab", req.establecimiento()).put("ptoEmi", req.puntoEmision()).put("secuencial", secuencial)
                .put("dirMatriz", e.dirMatriz());
        if (e.agenteRetencion() != null) it.put("agenteRetencion", e.agenteRetencion());
        if (e.contribuyenteRimpe() != null) it.put("contribuyenteRimpe", e.contribuyenteRimpe());

        var xml = XmlComprobante.construir(req.tipo(), req.version(), it, req.contenido());
        Resumen r = Resumen.de(xml.getDocumentElement(), tipo.codDoc());
        firmador(e).firmar(xml);

        long id = db.sql("""
                        insert into comprobante (tenant_id, emisor_id, punto_emision_id, cod_doc, secuencial, clave_acceso,
                                                 idempotency_key, estado, fecha_emision, solicitud, xml_firmado,
                                                 subtotal, iva, importe_total, receptor_identificacion, receptor_razon_social)
                        values (?, ?, ?, ?, ?, ?, ?, 'PENDIENTE', ?, ?::jsonb, ?, ?, ?, ?, ?, ?) returning id""")
                .params(tenant, e.id(), e.puntoId(), tipo.codDoc(), secuencial, clave, idempotencyKey,
                        LocalDate.parse(fecha, DDMMYYYY), json.writeValueAsString(req), SriClient.serializar(xml),
                        r.subtotal(), r.iva(), r.total(), r.identificacion(), r.razonSocial())
                .query(Long.class).single();
        auditoria.registrar(s, "COMPROBANTE_EMITIDO", Map.of("claveAcceso", clave));
        registry.counter("sri.comprobantes.emitidos", "codDoc", tipo.codDoc()).increment();
        return id;
    }

    private Firmador firmador(Emisor e) {
        return firmadores.computeIfAbsent(e.id() + ":" + e.certHuella(), k -> {
            record Cert(byte[] certP12, byte[] certClave) {}
            Cert c = db.sql("select cert_p12, cert_clave from emisor where id = ?").param(e.id()).query(Cert.class).single();
            return new Firmador(cifrado.descifrar(c.certP12()),
                    new String(cifrado.descifrar(c.certClave()), StandardCharsets.UTF_8).toCharArray());
        });
    }

    record Trabajo(long id, String estado, String claveAcceso, String xmlFirmado, int intentos) {}

    /**
     * Avanza el comprobante con el SRI. Lo "reclama" moviendo proximo_intento 2 minutos adelante,
     * así la petición HTTP y el worker (o varias réplicas) nunca lo procesan a la vez.
     */
    public void procesar(long id) {
        Trabajo t = db.sql("""
                        update comprobante set proximo_intento = now() + interval '2 minutes'
                        where id = ? and estado in ('PENDIENTE', 'EN_PROCESO') and proximo_intento <= now()
                        returning id, estado, clave_acceso, xml_firmado, intentos""")
                .param(id).query(Trabajo.class).optional().orElse(null);
        if (t == null) return;
        try {
            boolean recienEnviado = false;
            if (t.estado().equals("PENDIENTE")) {
                var r = medir("recepcion", () -> sri.enviar(t.xmlFirmado().getBytes(StandardCharsets.UTF_8)));
                // 43 = clave ya registrada, 70 = en procesamiento: el SRI ya lo tiene, solo falta autorizar.
                boolean recibido = "RECIBIDA".equals(r.estado())
                        || r.mensajes().stream().anyMatch(m -> Set.of("43", "70").contains(m.get("identificador")));
                if (!recibido) {
                    terminar(id, "DEVUELTA", r.mensajes(), null);
                    return;
                }
                db.sql("update comprobante set estado = 'EN_PROCESO', actualizado_en = now() where id = ?").param(id).update();
                recienEnviado = true;
            }
            SriClient.Autorizacion a = null;
            for (int i = 0; i < 3 && a == null; i++) {
                if (recienEnviado || i > 0) Thread.sleep(1000L * (i + 1));
                a = medir("autorizacion", () -> sri.consultar(t.claveAcceso()));
            }
            if (a == null) reprogramar(t, null);
            else terminar(id, "AUTORIZADO".equals(a.estado()) ? "AUTORIZADO" : "NO_AUTORIZADO", a.mensajes(), a);
        } catch (Exception ex) {
            log.warn("Comprobante {}: error hablando con el SRI: {}", t.claveAcceso(), ex.toString());
            reprogramar(t, ex.getMessage());
        }
    }

    /** Mide la llamada al SRI (Prometheus) y le avisa al monitor si respondió o no. */
    private <T> T medir(String operacion, java.util.concurrent.Callable<T> llamada) throws Exception {
        Timer.Sample muestra = Timer.start(registry);
        try {
            T r = llamada.call();
            muestra.stop(registry.timer("sri.llamadas", "operacion", operacion, "resultado", "ok"));
            monitor.exito();
            return r;
        } catch (Exception e) {
            muestra.stop(registry.timer("sri.llamadas", "operacion", operacion, "resultado", "error"));
            monitor.fallo(e.toString());
            throw e;
        }
    }

    private void terminar(long id, String estado, List<Map<String, String>> mensajes, SriClient.Autorizacion a) {
        registry.counter("sri.comprobantes.finalizados", "estado", estado).increment();
        OffsetDateTime fecha = null;
        try {
            if (a != null && a.fechaAutorizacion() != null) fecha = OffsetDateTime.parse(a.fechaAutorizacion());
        } catch (Exception ignored) {
            // Formato inesperado: la fecha igual queda dentro del XML autorizado.
        }
        OffsetDateTime f = fecha;
        // Estado final y su evento de webhook en la misma transacción: no se pierde ninguna notificación.
        tx.executeWithoutResult(st -> {
            db.sql("""
                            update comprobante set estado = ?, mensajes = ?::jsonb, xml_autorizado = ?, numero_autorizacion = ?,
                                                   fecha_autorizacion = ?, actualizado_en = now()
                            where id = ?""")
                    .params(estado, json.writeValueAsString(mensajes),
                            a != null && estado.equals("AUTORIZADO") ? a.xml() : null,
                            a == null ? null : a.numeroAutorizacion(), f, id)
                    .update();
            webhooks.encolar(id);
            if (estado.equals("AUTORIZADO")) correos.encolarAutomatico(id);
        });
        webhooks.despacharPronto();
        correos.despacharPronto();
    }

    private void reprogramar(Trabajo t, String error) {
        int intentos = t.intentos() + 1;
        long espera = Math.min(10L << Math.min(intentos, 20), 3600); // 20s, 40s, 80s... tope 1 hora
        var mensajes = error == null ? List.of() : List.of(Map.of("tipo", "ERROR_CONEXION", "mensaje", error));
        boolean agotado = intentos >= MAX_INTENTOS;
        tx.executeWithoutResult(st -> {
            db.sql("""
                            update comprobante set intentos = ?, proximo_intento = now() + make_interval(secs => ?),
                                   estado = case when ? then 'ERROR' else estado end,
                                   mensajes = case when ? then ?::jsonb else mensajes end, actualizado_en = now()
                            where id = ?""")
                    .params(intentos, espera, agotado, error != null, json.writeValueAsString(mensajes), t.id())
                    .update();
            if (agotado) webhooks.encolar(t.id());
        });
        if (agotado) webhooks.despacharPronto();
    }

    /** Reintenta en segundo plano lo que quedó pendiente (SRI caído, sin respuesta de autorización, etc.). */
    @Scheduled(fixedDelayString = "${app.worker-intervalo-ms:5000}")
    public void worker() {
        List<Long> ids = db.sql("""
                        select id from comprobante where estado in ('PENDIENTE', 'EN_PROCESO') and proximo_intento <= now()
                        order by proximo_intento limit 50""")
                .query(Long.class).list();
        if (ids.isEmpty()) return;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ids.forEach(id -> ex.submit(() -> procesar(id)));
        }
    }

    /** Pone al frente de la cola los pendientes del tenant y los procesa en paralelo. */
    public int sincronizar(long tenant) {
        List<Long> ids = db.sql("""
                        update comprobante set proximo_intento = now()
                        where tenant_id = ? and estado in ('PENDIENTE', 'EN_PROCESO') returning id""")
                .param(tenant).query(Long.class).list();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ids.forEach(id -> ex.submit(() -> procesar(id)));
        }
        return ids.size();
    }

    /** Fuerza el procesamiento inmediato. Con reiniciar=true un ERROR vuelve a PENDIENTE con contador en cero. */
    public void forzar(long tenant, String clave, boolean reiniciar) {
        int n = db.sql("""
                        update comprobante set proximo_intento = now(),
                               estado = case when ? and estado = 'ERROR' then 'PENDIENTE' else estado end,
                               intentos = case when ? then 0 else intentos end
                        where tenant_id = ? and clave_acceso = ?""")
                .params(reiniciar, reiniciar, tenant, clave).update();
        if (n == 0) throw new NoSuchElementException("Comprobante no encontrado");
        db.sql("select id from comprobante where tenant_id = ? and clave_acceso = ?").params(tenant, clave)
                .query(Long.class).optional().ifPresent(this::procesar);
    }

    /** Totales y receptor que se guardan en columnas para los reportes. */
    record Resumen(BigDecimal subtotal, BigDecimal iva, BigDecimal total, String identificacion, String razonSocial) {

        static Resumen de(org.w3c.dom.Element raiz, String codDoc) {
            BigDecimal subtotal = null, iva = null, total = switch (codDoc) {
                case "01", "03" -> num(raiz, "importeTotal");
                case "04" -> num(raiz, "valorModificacion");
                case "05" -> num(raiz, "valorTotal");
                case "07" -> suma(raiz, "valorRetenido");
                default -> null;
            };
            if (Set.of("01", "03", "04", "05").contains(codDoc)) {
                subtotal = num(raiz, "totalSinImpuestos");
                iva = suma(raiz, codDoc.equals("05") ? "impuestos" : "totalConImpuestos", "valor");
            }
            String id = null, nombre = null;
            for (String quien : List.of("Comprador", "Proveedor", "SujetoRetenido", "Destinatario")) {
                if (id == null) id = texto(raiz, "identificacion" + quien);
                if (nombre == null) nombre = texto(raiz, "razonSocial" + quien);
            }
            return new Resumen(subtotal, iva, total, id, nombre);
        }

        private static String texto(org.w3c.dom.Element e, String tag) {
            var l = e.getElementsByTagName(tag);
            return l.getLength() == 0 ? null : l.item(0).getTextContent();
        }

        private static BigDecimal num(org.w3c.dom.Element e, String tag) {
            String t = texto(e, tag);
            return t == null ? null : new BigDecimal(t);
        }

        private static BigDecimal suma(org.w3c.dom.Element e, String tag) {
            var l = e.getElementsByTagName(tag);
            BigDecimal s = BigDecimal.ZERO;
            for (int i = 0; i < l.getLength(); i++) s = s.add(new BigDecimal(l.item(i).getTextContent()));
            return s;
        }

        /** Suma los <valor> dentro del primer contenedor (los de totales, no los de cada detalle). */
        private static BigDecimal suma(org.w3c.dom.Element e, String contenedor, String tag) {
            var l = e.getElementsByTagName(contenedor);
            return l.getLength() == 0 ? BigDecimal.ZERO : suma((org.w3c.dom.Element) l.item(0), tag);
        }
    }
}
