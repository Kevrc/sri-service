package ec.facturacion.sri;

import ec.facturacion.sri.sri.SriClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/** Endpoints simplificados: cálculos, XML válido contra los XSD oficiales del SRI, anulación y sincronización. */
class SimplificadosTest extends IntegracionBase {
    String admin, user, ruc;

    @BeforeEach
    void emisorNuevo() throws Exception {
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        doReturn(new SriClient.Autorizacion("AUTORIZADO", "123", "2026-10-31T10:00:00-05:00", List.of(), "<autorizacion/>"))
                .when(sri).consultar(anyString());

        Empresa e = nuevaEmpresa();
        admin = e.admin();
        user = e.user();
        ruc = e.ruc();
    }

    Map<String, Object> base(Object... kv) {
        var m = new java.util.LinkedHashMap<String, Object>(Map.of("ruc", ruc, "establecimiento", "002", "puntoEmision", "003",
                "fechaEmision", "2026-10-31"));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final Map<String, Object> CLIENTE = Map.of("tipoIdentificacion", "05", "identificacion", "0926789017",
            "razonSocial", "JUAN PEREZ", "email", "juan@example.com");

    Document xml(String claveAcceso) throws Exception {
        String s = db.sql("select xml_firmado from comprobante where clave_acceso = ?").param(claveAcceso).query(String.class).single();
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(s.getBytes()));
    }

    void validarXsd(String claveAcceso, String xsd) throws Exception {
        String s = db.sql("select xml_firmado from comprobante where clave_acceso = ?").param(claveAcceso).query(String.class).single();
        var schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(getClass().getResource("/xsd/" + xsd));
        try {
            schema.newValidator().validate(new StreamSource(new StringReader(s)));
        } catch (SAXException e) {
            fail("No cumple " + xsd + ": " + e.getMessage() + "\n" + s);
        }
    }

    static String textoPdf(byte[] pdf) throws Exception {
        assertEquals("%PDF", new String(pdf, 0, 4));
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("[ \\t]+", " ");
        }
    }

    static String texto(Document d, String tag, int i) {
        return d.getElementsByTagName(tag).item(i).getTextContent();
    }

    Resp emitir(String ruta, Object body) throws Exception {
        Resp r = llamar("POST", ruta, user, body);
        assertEquals(201, r.status(), String.valueOf(r.body()));
        assertEquals("AUTORIZADO", r.body().get("estado").asString());
        String clave = r.body().get("claveAcceso").asString();
        String texto = textoPdf(descargar("/sri/comprobantes/" + clave + "/ride", user));
        assertTrue(texto.contains(clave), "el RIDE debe mostrar la clave de acceso");
        return r;
    }

    @Test
    void facturaCalculaTotalesYCumpleXsd() throws Exception {
        Resp r = emitir("/sri/factura/emitir", base("comprador", CLIENTE,
                "items", List.of(
                        Map.of("codigo", "A1", "descripcion", "Tornillo", "cantidad", 3, "precioUnitario", 1.333333, "iva", "4",
                                "detallesAdicionales", Map.of("Color", "Gris")),
                        Map.of("codigo", "B2", "descripcion", "Libro", "cantidad", 1, "precioUnitario", 10, "descuento", 1, "iva", "0")),
                "pagos", List.of(Map.of("formaPago", "19")),
                "infoAdicional", Map.of("Pedido", "PED-77")));
        String clave = r.body().get("claveAcceso").asString();
        validarXsd(clave, "factura_V1.1.0.xsd");

        Document d = xml(clave);
        assertEquals("4.00", texto(d, "precioTotalSinImpuesto", 0));   // 3 x 1.333333 = 3.999999
        assertEquals("0.60", texto(d, "valor", 0));                    // IVA 15% de la línea 1
        assertEquals("13.00", texto(d, "totalSinImpuestos", 0));
        assertEquals("1.00", texto(d, "totalDescuento", 0));
        assertEquals("13.60", texto(d, "importeTotal", 0));
        assertEquals("13.60", texto(d, "total", 0));                   // el único pago toma el total
        assertEquals("Sucursal Norte", texto(d, "dirEstablecimiento", 0));
        assertEquals("12345", texto(d, "contribuyenteEspecial", 0));
        assertEquals("SI", texto(d, "obligadoContabilidad", 0));
        assertEquals(2, d.getElementsByTagName("campoAdicional").getLength()); // Email + Pedido
        assertTrue(clave.startsWith("3110202601" + ruc + "1002003000000001"));
    }

    @Test
    void notaCreditoCumpleXsd() throws Exception {
        Resp r = emitir("/sri/nota-credito/emitir", base("comprador", CLIENTE,
                "docModificado", Map.of("numero", "002-003-000000001", "fechaEmision", "2026-10-30"),
                "motivo", "Devolución de mercadería",
                "items", List.of(Map.of("codigo", "A1", "descripcion", "Tornillo", "cantidad", 2, "precioUnitario", 5, "iva", "4"))));
        String clave = r.body().get("claveAcceso").asString();
        validarXsd(clave, "notaCredito_V1.1.0.xsd");
        Document d = xml(clave);
        assertEquals("11.50", texto(d, "valorModificacion", 0));
        assertEquals("30/10/2026", texto(d, "fechaEmisionDocSustento", 0));
        assertEquals("04", r.body().get("codDoc").asString());
    }

    @Test
    void retencionCalculaValoresYCumpleXsd() throws Exception {
        Resp r = emitir("/sri/retencion/emitir", base(
                "sujetoRetenido", Map.of("tipoIdentificacion", "04", "identificacion", "1790011674001", "razonSocial", "PROVEEDOR S.A."),
                "docsSustento", List.of(Map.of("codSustento", "01", "codDoc", "01", "numero", "001-001-000000123",
                        "fechaEmision", "2026-10-30",
                        "impuestos", List.of(Map.of("iva", "4", "baseImponible", 100)),
                        "retenciones", List.of(
                                Map.of("codigo", "1", "codigoRetencion", "343", "baseImponible", 100, "porcentaje", 1),
                                Map.of("codigo", "2", "codigoRetencion", "1", "baseImponible", 15, "porcentaje", 30)),
                        "pagos", List.of(Map.of("formaPago", "20"))))));
        String clave = r.body().get("claveAcceso").asString();
        validarXsd(clave, "comprobanteRetencion_V2.0.0.xsd");
        Document d = xml(clave);
        assertEquals("1.00", texto(d, "valorRetenido", 0));
        assertEquals("4.50", texto(d, "valorRetenido", 1));
        assertEquals("115.00", texto(d, "importeTotal", 0));
        assertEquals("001001000000123", texto(d, "numDocSustento", 0));
        assertEquals("10/2026", texto(d, "periodoFiscal", 0));
        assertEquals("NO", texto(d, "parteRel", 0));
    }

    @Test
    void notaDebitoGuiaYLiquidacion() throws Exception {
        Resp nd = emitir("/sri/nota-debito/emitir", base("comprador", CLIENTE,
                "docModificado", Map.of("numero", "002-003-000000001", "fechaEmision", "2026-10-30"),
                "motivos", List.of(Map.of("razon", "Intereses por mora", "valor", 20)), "iva", "4"));
        Document d = xml(nd.body().get("claveAcceso").asString());
        assertEquals("notaDebito", d.getDocumentElement().getTagName());
        assertEquals("23.00", texto(d, "valorTotal", 0));

        Resp g = emitir("/sri/guia-remision/emitir", Map.of("ruc", ruc, "establecimiento", "002", "puntoEmision", "003",
                "transportista", Map.of("tipoIdentificacion", "05", "identificacion", "0912345678", "razonSocial", "TRANSPORTES X"),
                "placa", "GBA1234", "dirPartida", "Bodega Norte",
                "fechaInicioTransporte", "2026-10-31", "fechaFinTransporte", "2026-11-01",
                "destinatarios", List.of(Map.of("identificacion", "0926789017", "razonSocial", "JUAN PEREZ",
                        "direccion", "Guayaquil", "motivoTraslado", "Venta",
                        "docSustento", Map.of("numero", "002-003-000000001", "fechaEmision", "2026-10-31"),
                        "items", List.of(Map.of("codigo", "A1", "descripcion", "Tornillo", "cantidad", 3))))));
        d = xml(g.body().get("claveAcceso").asString());
        assertEquals("06", g.body().get("codDoc").asString());
        assertEquals("31/10/2026", texto(d, "fechaIniTransporte", 0));
        assertEquals("01", texto(d, "codDocSustento", 0));

        Resp l = emitir("/sri/liquidacion-compra/emitir", base(
                "proveedor", Map.of("tipoIdentificacion", "05", "identificacion", "0926789017", "razonSocial", "AGRICULTOR"),
                "items", List.of(Map.of("descripcion", "Cacao", "cantidad", 10, "precioUnitario", 2.5, "iva", "0")),
                "pagos", List.of(Map.of("formaPago", "01"))));
        d = xml(l.body().get("claveAcceso").asString());
        assertEquals("liquidacionCompra", d.getDocumentElement().getTagName());
        assertEquals("25.00", texto(d, "importeTotal", 0));
    }

    @Test
    void validaciones() throws Exception {
        var pagosMal = base("comprador", CLIENTE,
                "items", List.of(Map.of("descripcion", "X", "cantidad", 1, "precioUnitario", 10, "iva", "4")),
                "pagos", List.of(Map.of("formaPago", "01", "total", 5)));
        assertEquals(400, llamar("POST", "/sri/factura/emitir", user, pagosMal).status());
        var ivaMal = base("comprador", CLIENTE,
                "items", List.of(Map.of("descripcion", "X", "cantidad", 1, "precioUnitario", 10, "iva", "99")),
                "pagos", List.of(Map.of("formaPago", "01")));
        assertEquals(400, llamar("POST", "/sri/factura/emitir", user, ivaMal).status());
        assertEquals(400, llamar("POST", "/sri/factura/emitir", user, base("items", List.of())).status());
        // Ningún intento fallido consumió secuencial
        assertEquals(0, db.sql("select count(*) from secuencial s join punto_emision p on p.id = s.punto_emision_id join emisor e on e.id = p.emisor_id where e.ruc = ?")
                .param(ruc).query(Long.class).single());
    }

    @Test
    void anularYSincronizar() throws Exception {
        var factura = base("comprador", CLIENTE,
                "items", List.of(Map.of("descripcion", "X", "cantidad", 1, "precioUnitario", 10, "iva", "4")),
                "pagos", List.of(Map.of("formaPago", "01")));
        String clave = emitir("/sri/factura/emitir", factura).body().get("claveAcceso").asString();
        assertEquals(403, llamar("PATCH", "/sri/comprobantes/" + clave + "/anular", user, Map.of("motivo", "error")).status());
        Resp a = llamar("PATCH", "/sri/comprobantes/" + clave + "/anular", admin, Map.of("motivo", "Error en el precio"));
        assertEquals("ANULADO", a.body().get("estado").asString());
        assertEquals(409, llamar("PATCH", "/sri/comprobantes/" + clave + "/anular", admin, Map.of("motivo", "otra vez")).status());

        doThrow(new IOException("SRI caído")).when(sri).enviar(any());
        Resp p = llamar("POST", "/sri/factura/emitir", user, factura);
        assertEquals("PENDIENTE", p.body().get("estado").asString());
        doReturn(new SriClient.Respuesta("RECIBIDA", List.of())).when(sri).enviar(any());
        assertEquals(1, llamar("POST", "/sri/sincronizar", user, null).body().get("procesados").asInt());
        assertEquals("AUTORIZADO", llamar("GET", "/sri/comprobantes/" + p.body().get("claveAcceso").asString(), user, null).body().get("estado").asString());
    }

    @Test
    void catalogos() throws Exception {
        Resp r = llamar("GET", "/catalogos/impuestos", user, null);
        assertEquals(200, r.status());
        assertTrue(r.body().toString().contains("\"codigo\":\"4\",\"descripcion\":\"15%\""));
        assertEquals(404, llamar("GET", "/catalogos/nada", user, null).status());
    }
}
