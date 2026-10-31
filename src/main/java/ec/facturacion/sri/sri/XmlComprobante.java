package ec.facturacion.sri.sri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.Map;

/**
 * Arma el XML del comprobante. El contenido sigue la misma estructura y orden que el XSD del SRI,
 * así sirve para los 6 tipos sin código específico por tipo:
 * claves "@x" son atributos, "#text" es texto, los arreglos repiten el elemento.
 */
public final class XmlComprobante {

    public record Tipo(String codDoc, String info) {}

    public static final Map<String, Tipo> TIPOS = Map.of(
            "factura", new Tipo("01", "infoFactura"),
            "liquidacionCompra", new Tipo("03", "infoLiquidacionCompra"),
            "notaCredito", new Tipo("04", "infoNotaCredito"),
            "notaDebito", new Tipo("05", "infoNotaDebito"),
            "guiaRemision", new Tipo("06", "infoGuiaRemision"),
            "comprobanteRetencion", new Tipo("07", "infoCompRetencion"));

    public static Tipo tipo(String nombre) {
        Tipo t = TIPOS.get(nombre);
        if (t == null) throw new IllegalArgumentException("tipo debe ser uno de " + TIPOS.keySet());
        return t;
    }

    /** dd/mm/aaaa del comprobante. La guía de remisión no tiene fechaEmision; usa la de inicio de transporte. */
    public static String fecha(String tipoNombre, JsonNode contenido) {
        JsonNode info = contenido.path(tipo(tipoNombre).info());
        if (!info.isObject()) throw new IllegalArgumentException("Falta " + tipo(tipoNombre).info());
        String f = info.has("fechaEmision") ? info.get("fechaEmision").asString() : info.path("fechaIniTransporte").asString();
        if (!f.matches("\\d{2}/\\d{2}/\\d{4}")) throw new IllegalArgumentException("Fecha inválida, use dd/mm/aaaa");
        return f;
    }

    /** infoTributaria debe venir completo y en el orden del XSD; el resto de nodos sale de contenido. */
    public static Document construir(String tipoNombre, String version, ObjectNode infoTributaria, JsonNode contenido) throws Exception {
        if (!version.matches("\\d\\.\\d\\.\\d")) throw new IllegalArgumentException("version inválida (ej. 1.1.0)");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document xml = f.newDocumentBuilder().newDocument();
        Element root = xml.createElement(tipoNombre);
        root.setAttribute("id", "comprobante");
        root.setAttribute("version", version);
        root.setIdAttribute("id", true);
        xml.appendChild(root);

        agregar(xml, root, "infoTributaria", infoTributaria);
        for (var e : contenido.properties()) {
            if (!e.getKey().equals("infoTributaria")) agregar(xml, root, e.getKey(), e.getValue());
        }
        return xml;
    }

    private static void agregar(Document xml, Element padre, String nombre, JsonNode valor) {
        if (valor == null || valor.isNull()) return;
        if (!nombre.matches("[A-Za-z][A-Za-z0-9]*")) throw new IllegalArgumentException("Nombre de campo inválido: " + nombre);
        if (valor.isArray()) {
            for (JsonNode v : valor) agregar(xml, padre, nombre, v);
            return;
        }
        Element el = xml.createElement(nombre);
        padre.appendChild(el);
        if (valor.isObject()) {
            for (var e : valor.properties()) {
                String k = e.getKey();
                if (k.startsWith("@")) el.setAttribute(k.substring(1), texto(e.getValue()));
                else if (k.equals("#text")) el.setTextContent(texto(e.getValue()));
                else agregar(xml, el, k, e.getValue());
            }
        } else {
            el.setTextContent(texto(valor));
        }
    }

    // Números como texto plano: 12.50 se mantiene "12.50" y nunca sale en notación 1.0E7.
    private static String texto(JsonNode v) {
        return v.isNumber() ? v.decimalValue().toPlainString() : v.asString();
    }
}
