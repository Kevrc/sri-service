package ec.facturacion.sri;

import ec.facturacion.sri.sri.SriClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

class ReportesTest extends IntegracionBase {

    @Test
    void resumenYCsv() throws Exception {
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        doReturn(new SriClient.Autorizacion("AUTORIZADO", "123", "2026-10-31T10:00:00-05:00", List.of(), "<autorizacion/>"))
                .when(sri).consultar(anyString());
        Empresa e = nuevaEmpresa();
        Map<String, Object> base = Map.of("ruc", e.ruc(), "establecimiento", "002", "puntoEmision", "003", "fechaEmision", "2026-10-15");
        var malicioso = Map.of("tipoIdentificacion", "05", "identificacion", "0926789017",
                "razonSocial", "=HYPERLINK(\"http://x\",\"clic\")");

        var factura = new java.util.HashMap<>(base);
        factura.putAll(Map.of("comprador", malicioso,
                "items", List.of(Map.of("descripcion", "Servicio", "cantidad", 1, "precioUnitario", 100, "iva", "4")),
                "pagos", List.of(Map.of("formaPago", "20"))));
        assertEquals(201, llamar("POST", "/sri/factura/emitir", e.user(), factura).status());

        var nc = new java.util.HashMap<>(base);
        nc.putAll(Map.of("comprador", malicioso, "motivo", "Descuento",
                "docModificado", Map.of("numero", "002-003-000000001", "fechaEmision", "2026-10-15"),
                "items", List.of(Map.of("descripcion", "Servicio", "cantidad", 1, "precioUnitario", 20, "iva", "4"))));
        assertEquals(201, llamar("POST", "/sri/nota-credito/emitir", e.user(), nc).status());

        var ret = new java.util.HashMap<>(base);
        ret.putAll(Map.of("sujetoRetenido", Map.of("tipoIdentificacion", "04", "identificacion", "1790011674001", "razonSocial", "PROV"),
                "docsSustento", List.of(Map.of("codSustento", "01", "codDoc", "01", "numero", "001-001-000000009", "fechaEmision", "2026-10-14",
                        "impuestos", List.of(Map.of("iva", "4", "baseImponible", 100)),
                        "retenciones", List.of(Map.of("codigo", "1", "codigoRetencion", "343", "baseImponible", 100, "porcentaje", 1)),
                        "pagos", List.of(Map.of("formaPago", "20"))))));
        assertEquals(201, llamar("POST", "/sri/retencion/emitir", e.user(), ret).status());

        // Una factura rechazada no cuenta en las ventas
        doReturn(new SriClient.Respuesta("DEVUELTA", List.of(Map.of("identificador", "35")))).when(sri).enviar(any());
        assertEquals("DEVUELTA", llamar("POST", "/sri/factura/emitir", e.user(), factura).body().get("estado").asString());

        var r = llamar("GET", "/reportes/resumen?desde=2026-10-01&hasta=2026-10-31", e.user(), null).body();
        assertEquals("80.00", r.get("ventasNetas").get("subtotal").decimalValue().toPlainString());   // 100 - 20
        assertEquals("12.00", r.get("ventasNetas").get("iva").decimalValue().toPlainString());        // 15 - 3
        assertEquals("92.00", r.get("ventasNetas").get("total").decimalValue().toPlainString());      // 115 - 23
        assertEquals("1.00", r.get("retencionesEmitidas").decimalValue().toPlainString());
        assertEquals(4, r.get("detalle").size()); // factura AUTORIZADO, factura DEVUELTA, NC, retención

        var csv = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/reportes/comprobantes.csv?desde=2026-10-01&hasta=2026-10-31"))
                .header("Authorization", "Bearer " + e.user()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, csv.statusCode());
        String[] lineas = csv.body().split("\r\n");
        assertTrue(lineas[0].startsWith("﻿fecha_emision,"));
        assertEquals(5, lineas.length);
        assertTrue(csv.body().contains("\"'=HYPERLINK(\"\"http://x\"\",\"\"clic\"\")\""), "la fórmula debe quedar neutralizada:\n" + csv.body());
        assertTrue(csv.body().contains(",FACTURA,002-003-000000001,"));

        assertEquals(400, llamar("GET", "/reportes/resumen?desde=2026-10-31&hasta=2026-10-01", e.user(), null).status());
        assertEquals(400, llamar("GET", "/reportes/resumen?desde=2024-01-01&hasta=2026-01-01", e.user(), null).status());
        Empresa otra = nuevaEmpresa();
        assertEquals(0, llamar("GET", "/reportes/resumen?desde=2026-10-01&hasta=2026-10-31", otra.user(), null).body().get("detalle").size());
    }

    @Test
    void swaggerDisponible() throws Exception {
        var docs = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api-docs")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, docs.statusCode());
        assertTrue(docs.body().contains("/sri/factura/emitir"));
        assertTrue(docs.body().contains("bearer"));
        var ui = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(ui.statusCode() == 200 || ui.statusCode() == 302, "Swagger UI: HTTP " + ui.statusCode());
    }
}
