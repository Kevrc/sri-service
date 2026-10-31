package ec.facturacion.sri;

import com.sun.net.httpserver.HttpServer;
import ec.facturacion.sri.sri.SriClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

class RideWebhooksTest extends IntegracionBase {

    record Recibido(Map<String, String> headers, String body) {}

    HttpServer receptor;
    final LinkedBlockingQueue<Recibido> recibidos = new LinkedBlockingQueue<>();
    volatile int respuesta = 200;
    Empresa emp;

    @BeforeEach
    void preparar() throws Exception {
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        doReturn(new SriClient.Autorizacion("AUTORIZADO", "123", "2026-10-31T10:00:00-05:00", List.of(), "<autorizacion/>"))
                .when(sri).consultar(anyString());
        emp = nuevaEmpresa();
        receptor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receptor.createContext("/hook", ex -> {
            var h = Map.of("evento", ex.getRequestHeaders().getFirst("X-SRI-Evento"),
                    "ts", ex.getRequestHeaders().getFirst("X-SRI-Timestamp"),
                    "firma", ex.getRequestHeaders().getFirst("X-SRI-Firma"));
            recibidos.add(new Recibido(h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(respuesta, -1);
            ex.close();
        });
        receptor.start();
    }

    @AfterEach
    void cerrar() {
        receptor.stop(0);
    }

    Map<String, Object> facturaSimple() {
        return Map.of("ruc", emp.ruc(), "establecimiento", "002", "puntoEmision", "003", "fechaEmision", "2026-10-31",
                "comprador", Map.of("tipoIdentificacion", "05", "identificacion", "0926789017", "razonSocial", "JUAN PEREZ"),
                "items", List.of(Map.of("codigo", "P1", "descripcion", "Servicio técnico", "cantidad", 1, "precioUnitario", 10, "iva", "4")),
                "pagos", List.of(Map.of("formaPago", "01")));
    }

    static String hmac(String secreto, String datos) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
    }

    JsonNode esperarEntrega(String clave, String estado) throws Exception {
        for (int i = 0; i < 50; i++) {
            JsonNode l = llamar("GET", "/webhooks/logs?claveAcceso=" + clave, emp.admin(), null).body().get("items");
            for (JsonNode x : l) if (x.get("estado").asString().equals(estado)) return x;
            Thread.sleep(100);
        }
        fail("La entrega de " + clave + " no llegó a " + estado);
        return null;
    }

    @Test
    void webhookFirmadoReintentosYReenvio() throws Exception {
        String url = "http://127.0.0.1:" + receptor.getAddress().getPort() + "/hook";
        assertEquals(403, llamar("POST", "/webhooks", emp.user(), Map.of("url", url)).status());
        assertEquals(400, llamar("POST", "/webhooks", emp.admin(), Map.of("url", "ftp://x.ec/hook")).status());
        assertEquals(400, llamar("POST", "/webhooks", emp.admin(), Map.of("url", url, "eventos", List.of("algo.raro"))).status());
        Resp w = llamar("POST", "/webhooks", emp.admin(), Map.of("url", url));
        assertEquals(201, w.status(), String.valueOf(w.body()));
        String secreto = w.body().get("secreto").asString();
        assertFalse(llamar("GET", "/webhooks", emp.admin(), null).body().toString().contains(secreto), "el secreto no se vuelve a mostrar");

        // Autorizado -> notificación firmada
        String clave = llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).body().get("claveAcceso").asString();
        Recibido r = recibidos.poll(5, TimeUnit.SECONDS);
        assertNotNull(r, "el webhook no llegó");
        assertEquals("comprobante.autorizado", r.headers().get("evento"));
        assertEquals("sha256=" + hmac(secreto, r.headers().get("ts") + "." + r.body()), r.headers().get("firma"));
        assertEquals(clave, json.readTree(r.body()).get("claveAcceso").asString());
        esperarEntrega(clave, "ENTREGADO");

        // Receptor caído: queda PENDIENTE con el error registrado; al arreglarlo, reenviar lo entrega
        respuesta = 500;
        assertEquals(200, llamar("PATCH", "/sri/comprobantes/" + clave + "/anular", emp.admin(), Map.of("motivo", "prueba")).status());
        assertEquals("comprobante.anulado", recibidos.poll(5, TimeUnit.SECONDS).headers().get("evento"));
        JsonNode pendiente = null;
        for (int i = 0; i < 50 && pendiente == null; i++) {
            for (JsonNode x : llamar("GET", "/webhooks/logs?estado=PENDIENTE", emp.admin(), null).body().get("items"))
                if (x.get("intentos").asInt() == 1) pendiente = x;
            Thread.sleep(100);
        }
        assertNotNull(pendiente);
        assertEquals(500, pendiente.get("respuestaStatus").asInt());
        respuesta = 200;
        assertEquals(200, llamar("POST", "/webhooks/logs/" + pendiente.get("id").asLong() + "/reenviar", emp.admin(), null).status());
        assertNotNull(recibidos.poll(5, TimeUnit.SECONDS));
        for (int i = 0; i < 50; i++) {
            if (llamar("GET", "/webhooks/logs?estado=PENDIENTE", emp.admin(), null).body().get("total").asLong() == 0) break;
            Thread.sleep(100);
        }
        assertEquals(0, llamar("GET", "/webhooks/logs?estado=PENDIENTE", emp.admin(), null).body().get("total").asLong());

        // Otro tenant no ve los logs
        Empresa otra = nuevaEmpresa();
        assertEquals(0, llamar("GET", "/webhooks/logs", otra.admin(), null).body().get("total").asLong());
    }

    @Test
    void rideConLogoAnuladoYPendiente() throws Exception {
        var img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        var png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        assertEquals(200, subirArchivo("/emisores/" + emp.emisorId() + "/logo", emp.admin(), png.toByteArray()));
        assertEquals(400, subirArchivo("/emisores/" + emp.emisorId() + "/logo", emp.admin(), "<svg onload=alert(1)>".getBytes()));

        String clave = llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).body().get("claveAcceso").asString();
        String texto = SimplificadosTest.textoPdf(descargar("/sri/comprobantes/" + clave + "/ride", emp.user()));
        for (String esperado : List.of("FACTURA", clave, "002-003-000000001", "JUAN PEREZ", "Servicio técnico",
                "VALOR TOTAL", "11.50", "Subtotal 15%", "PRUEBAS", "Sin utilización del sistema financiero"))
            assertTrue(texto.contains(esperado), "falta '" + esperado + "' en el RIDE:\n" + texto);

        llamar("PATCH", "/sri/comprobantes/" + clave + "/anular", emp.admin(), Map.of("motivo", "prueba"));
        assertTrue(SimplificadosTest.textoPdf(descargar("/sri/comprobantes/" + clave + "/ride", emp.user())).contains("ANULADO"));

        doThrow(new IOException("SRI caído")).when(sri).enviar(any());
        String pendiente = llamar("POST", "/sri/factura/emitir", emp.user(), facturaSimple()).body().get("claveAcceso").asString();
        assertEquals(409, llamar("GET", "/sri/comprobantes/" + pendiente + "/ride", emp.user(), null).status());

        Empresa otra = nuevaEmpresa();
        assertEquals(404, llamar("GET", "/sri/comprobantes/" + clave + "/ride", otra.user(), null).status());
    }

    int subirArchivo(String ruta, String token, byte[] datos) throws Exception {
        String limite = "----limite";
        var out = new ByteArrayOutputStream();
        out.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"archivo\"; filename=\"logo\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes());
        out.write(datos);
        out.write(("\r\n--" + limite + "--\r\n").getBytes());
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + ruta))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + limite)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
