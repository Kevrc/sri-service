package ec.facturacion.sri.sri;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FirmaTest {
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();

    @Test
    void digitoVerificadorModulo11() {
        // 4*7 + 1*6 + 2*5 + 3*4 + 4*3 + 5*2 = 78; 78 % 11 = 1; 11 - 1 = 10 -> 1
        assertEquals(1, ClaveAcceso.digitoVerificador("412345"));
        assertEquals(9, ClaveAcceso.digitoVerificador("1"));  // 11 - 2
        assertEquals(0, ClaveAcceso.digitoVerificador("0"));  // 11 -> 0
    }

    @Test
    void xmlEnOrdenYFirmaXadesValida() throws Exception {
        var req = JSON.readTree(Path.of("ejemplos/factura.json").toFile());
        var it = JSON.createObjectNode().put("ambiente", "1").put("tipoEmision", "1").put("razonSocial", "X")
                .put("ruc", "0999999999001").put("claveAcceso", "1".repeat(49)).put("codDoc", "01")
                .put("estab", "001").put("ptoEmi", "001").put("secuencial", "000000001").put("dirMatriz", "Y");
        var xml = XmlComprobante.construir("factura", "1.1.0", it, req.get("contenido"));

        Element root = xml.getDocumentElement();
        assertEquals("infoTributaria", root.getFirstChild().getNodeName());
        assertEquals("10.00", root.getElementsByTagName("totalSinImpuestos").item(0).getTextContent());
        assertEquals("Email", ((Element) root.getElementsByTagName("campoAdicional").item(0)).getAttribute("nombre"));

        Firmador f = new Firmador(Files.readAllBytes(Path.of("src/test/resources/prueba.p12")), "prueba123".toCharArray());
        f.firmar(xml);

        NodeList firmas = xml.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        NodeList sp = xml.getElementsByTagNameNS("http://uri.etsi.org/01903/v1.3.2#", "SignedProperties");
        assertEquals(1, sp.getLength(), "debe ser XAdES (SignedProperties)");
        ((Element) sp.item(0)).setIdAttribute("Id", true);
        var ctx = new DOMValidateContext(f.certificado.getPublicKey(), firmas.item(0));
        ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE); // permite SHA1
        assertTrue(XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx));
    }

    @Test
    void rechazaNombresDeCampoPeligrosos() {
        var contenido = JSON.createObjectNode();
        contenido.putObject("infoFactura").put("fechaEmision", "01/01/2026").put("<script>", "x");
        assertThrows(IllegalArgumentException.class,
                () -> XmlComprobante.construir("factura", "1.1.0", JSON.createObjectNode(), contenido));
    }

    @Test
    void p12ConClaveIncorrecta() throws Exception {
        byte[] p12 = Files.readAllBytes(Path.of("src/test/resources/prueba.p12"));
        assertThrows(IllegalArgumentException.class, () -> new Firmador(p12, "mala".toCharArray()));
    }
}
