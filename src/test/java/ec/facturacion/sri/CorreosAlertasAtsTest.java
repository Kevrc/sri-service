package ec.facturacion.sri;

import ec.facturacion.sri.sri.SriClient;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Email del RIDE/XML, alertas cuando el SRI cae, métricas Prometheus y ATS. */
@TestPropertySource(properties = {
        "app.correo.smtp-host=smtp.prueba.ec",
        "app.correo.remitente=facturas@prueba.ec",
        "app.alertas.emails=soporte@prueba.ec",
        "app.metrics-token=token-metricas-de-prueba"})
class CorreosAlertasAtsTest extends IntegracionBase {

    @MockitoBean
    JavaMailSender smtp;

    Empresa emp;

    @BeforeEach
    void preparar() throws Exception {
        reset(smtp);
        when(smtp.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        doReturn(new SriClient.Autorizacion("AUTORIZADO", "123", "2026-10-31T10:00:00-05:00", List.of(), "<autorizacion>ok</autorizacion>"))
                .when(sri).consultar(anyString());
        emp = nuevaEmpresa();
    }

    Map<String, Object> base(Object... kv) {
        var m = new HashMap<String, Object>(Map.of("ruc", emp.ruc(), "establecimiento", "002", "puntoEmision", "003", "fechaEmision", "2026-10-20"));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    Map<String, Object> factura(String email, int precio) {
        var comprador = new HashMap<String, Object>(Map.of("tipoIdentificacion", "05", "identificacion", "0926789017", "razonSocial", "JUAN PEREZ"));
        if (email != null) comprador.put("email", email);
        return base("comprador", comprador,
                "items", List.of(Map.of("descripcion", "Servicio", "cantidad", 1, "precioUnitario", precio, "iva", "4")),
                "pagos", List.of(Map.of("formaPago", "20")));
    }

    List<MimeMessage> enviados(int cuantos) throws Exception {
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp, timeout(5000).atLeast(cuantos)).send(captor.capture());
        return captor.getAllValues();
    }

    static List<String> adjuntos(MimeMessage m) throws Exception {
        List<String> nombres = new ArrayList<>();
        Multipart mp = (Multipart) m.getContent();
        for (int i = 0; i < mp.getCount(); i++)
            if (Part.ATTACHMENT.equalsIgnoreCase(mp.getBodyPart(i).getDisposition())) nombres.add(mp.getBodyPart(i).getFileName());
        return nombres;
    }

    @Test
    void emailConRideYXml() throws Exception {
        String clave = llamar("POST", "/sri/factura/emitir", emp.user(), factura("juan@example.com", 10)).body().get("claveAcceso").asString();
        MimeMessage m = enviados(1).getFirst();
        assertEquals("juan@example.com", m.getAllRecipients()[0].toString());
        assertTrue(m.getSubject().startsWith("Factura 002-003-000000001"), m.getSubject());
        assertEquals(List.of(clave + ".pdf", clave + ".xml"), adjuntos(m));
        for (int i = 0; i < 50 && !llamar("GET", "/sri/comprobantes/" + clave + "/emails", emp.user(), null).body().toString().contains("ENVIADO"); i++)
            Thread.sleep(100);
        assertEquals("ENVIADO", llamar("GET", "/sri/comprobantes/" + clave + "/emails", emp.user(), null).body().get(0).get("estado").asString());

        // Reenvío manual a otro correo; validación de direcciones
        assertEquals(202, llamar("POST", "/sri/comprobantes/" + clave + "/email", emp.user(), Map.of("destinatarios", List.of("otro@example.com"))).status());
        assertEquals("otro@example.com", enviados(2).get(1).getAllRecipients()[0].toString());
        assertEquals(400, llamar("POST", "/sri/comprobantes/" + clave + "/email", emp.user(), Map.of("destinatarios", List.of("no-es-email"))).status());

        // Sin email del cliente no se envía nada automáticamente
        clearInvocations(smtp);
        llamar("POST", "/sri/factura/emitir", emp.user(), factura(null, 10));
        Thread.sleep(500);
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void smtpCaidoQuedaPendiente() throws Exception {
        doThrow(new MailSendException("smtp caído")).when(smtp).send(any(MimeMessage.class));
        String clave = llamar("POST", "/sri/factura/emitir", emp.user(), factura("juan@example.com", 10)).body().get("claveAcceso").asString();
        for (int i = 0; i < 50; i++) {
            var h = llamar("GET", "/sri/comprobantes/" + clave + "/emails", emp.user(), null).body();
            if (h.size() > 0 && h.get(0).get("intentos").asInt() == 1) {
                assertEquals("PENDIENTE", h.get(0).get("estado").asString());
                assertTrue(h.get(0).get("error").asString().contains("smtp caído"));
                return;
            }
            Thread.sleep(100);
        }
        fail("el email fallido no quedó registrado para reintento");
    }

    @Test
    void alertaCuandoElSriCaeYCuandoVuelve() throws Exception {
        doThrow(new IOException("timeout")).when(sri).enviar(any());
        for (int i = 0; i < 5; i++) llamar("POST", "/sri/factura/emitir", emp.user(), factura(null, 10));
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp, timeout(5000)).send(captor.capture());
        assertEquals("[Servicio SRI] SRI no responde", captor.getValue().getSubject());
        assertArrayEquals(new String[]{"soporte@prueba.ec"}, captor.getValue().getTo());
        assertTrue(metricas().contains("sri_disponible 0.0"));

        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        llamar("POST", "/sri/sincronizar", emp.user(), null);
        verify(smtp, timeout(5000).times(2)).send(captor.capture());
        assertEquals("[Servicio SRI] SRI recuperado", captor.getAllValues().getLast().getSubject());
        String m = metricas();
        assertTrue(m.contains("sri_disponible 1.0"));
        assertTrue(m.contains("sri_llamadas_seconds_count{operacion=\"recepcion\",resultado=\"error\"}"), m);
        assertTrue(m.contains("sri_comprobantes_pendientes"));
    }

    String metricas() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/prometheus"))
                .header("Authorization", "Bearer token-metricas-de-prueba").build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void metricasProtegidas() throws Exception {
        for (String auth : new String[]{null, "Bearer otro-token", "Bearer " + access("root@test.ec", "SuperClave-123456")}) {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/prometheus"));
            if (auth != null) b.header("Authorization", auth);
            int st = http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
            assertTrue(st == 401 || st == 403, "sin el token correcto no debe abrir: " + st);
        }
        assertTrue(metricas().contains("sri_disponible"));
    }

    @Test
    void ats() throws Exception {
        llamar("POST", "/sri/factura/emitir", emp.user(), factura(null, 100));
        llamar("POST", "/sri/nota-credito/emitir", emp.user(), base(
                "comprador", Map.of("tipoIdentificacion", "05", "identificacion", "0926789017", "razonSocial", "JUAN PEREZ"),
                "docModificado", Map.of("numero", "002-003-000000001", "fechaEmision", "2026-10-20"), "motivo", "Descuento",
                "items", List.of(Map.of("descripcion", "Servicio", "cantidad", 1, "precioUnitario", 30, "iva", "4"))));
        llamar("POST", "/sri/retencion/emitir", emp.user(), base(
                "sujetoRetenido", Map.of("tipoIdentificacion", "04", "identificacion", "1790011674001", "razonSocial", "Proveedor Ñandú & Cía."),
                "docsSustento", List.of(Map.of("codSustento", "01", "codDoc", "01", "numero", "001-002-000000123", "fechaEmision", "2026-10-19",
                        "numAutorizacion", "1910202601179001167400110010020000001231234567811",
                        "impuestos", List.of(Map.of("iva", "4", "baseImponible", 100), Map.of("iva", "0", "baseImponible", 20)),
                        "retenciones", List.of(
                                Map.of("codigo", "1", "codigoRetencion", "312", "baseImponible", 120, "porcentaje", 1.75),
                                Map.of("codigo", "2", "codigoRetencion", "1", "baseImponible", 15, "porcentaje", 30)),
                        "pagos", List.of(Map.of("formaPago", "20"))))));
        llamar("POST", "/sri/liquidacion-compra/emitir", emp.user(), base(
                "proveedor", Map.of("tipoIdentificacion", "05", "identificacion", "0912345678", "razonSocial", "AGRICULTOR"),
                "items", List.of(Map.of("descripcion", "Cacao", "cantidad", 10, "precioUnitario", 5, "iva", "0")),
                "pagos", List.of(Map.of("formaPago", "01"))));

        String ruta = "/reportes/ats?ruc=" + emp.ruc() + "&anio=2026&mes=10";
        assertEquals(403, llamar("GET", ruta, emp.user(), null).status());
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + ruta))
                .header("Authorization", "Bearer " + emp.admin()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, r.statusCode(), r.body());
        Element iva = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(r.body().getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        assertEquals("iva", iva.getTagName());
        assertEquals("10", t(iva, "Mes"));
        assertEquals("70.00", t(iva, "totalVentas"));          // factura 100 − nota de crédito 30
        assertEquals("70.00", t(iva, "ventasEstab"));
        assertEquals(2, iva.getElementsByTagName("detalleCompras").getLength());

        Element ret = (Element) iva.getElementsByTagName("detalleCompras").item(0);
        assertEquals("01", t(ret, "tpIdProv"));
        assertEquals("001", t(ret, "establecimiento"));
        assertEquals("000000123", t(ret, "secuencial"));
        assertEquals("100.00", t(ret, "baseImpGrav"));
        assertEquals("20.00", t(ret, "baseImponible"));
        assertEquals("15.00", t(ret, "montoIva"));
        assertEquals("4.50", t(ret, "valorRetBienes"));        // IVA 30%
        assertEquals("312", t(ret, "codRetAir"));
        assertEquals("2.10", t(ret, "valRetAir"));             // 120 x 1.75%
        assertEquals("002", t(ret, "estabRetencion1"));

        Element liq = (Element) iva.getElementsByTagName("detalleCompras").item(1);
        assertEquals("03", t(liq, "tipoComprobante"));
        assertEquals("02", t(liq, "tpIdProv"));
        assertEquals("50.00", t(liq, "baseImponible"));

        var resumen = llamar("GET", "/reportes/ats/resumen?ruc=" + emp.ruc() + "&anio=2026&mes=10", emp.admin(), null).body();
        assertEquals(2, resumen.get("compras").asInt());
        assertTrue(resumen.get("advertencias").toString().contains("sustento tributario 01"));
        assertEquals(404, llamar("GET", "/reportes/ats?ruc=0999999999001&anio=2026&mes=10", emp.admin(), null).status());
    }

    static String t(Element e, String tag) {
        return e.getElementsByTagName(tag).item(0).getTextContent();
    }

    @Test
    void normalizaRazonSocialParaDimm() {
        assertEquals("PROVEEDOR NANDU CIA", ec.facturacion.sri.reportes.Ats.normalizar("Proveedor Ñandú & Cía."));
    }
}
