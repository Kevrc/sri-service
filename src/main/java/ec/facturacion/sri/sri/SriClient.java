package ec.facturacion.sri.sri;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cliente SOAP de los web services offline del SRI (recepción y autorización). */
@Component
public class SriClient {
    private final String recepcionUrl, autorizacionUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public SriClient(@Value("${app.sri-ambiente}") String ambiente) {
        if (!ambiente.matches("[12]")) throw new IllegalStateException("SRI_AMBIENTE debe ser 1 (pruebas) o 2 (producción)");
        String host = ambiente.equals("2") ? "https://cel.sri.gob.ec" : "https://celcer.sri.gob.ec";
        recepcionUrl = host + "/comprobantes-electronicos-ws/RecepcionComprobantesOffline";
        autorizacionUrl = host + "/comprobantes-electronicos-ws/AutorizacionComprobantesOffline";
    }

    public record Respuesta(String estado, List<Map<String, String>> mensajes) {}

    public record Autorizacion(String estado, String numeroAutorizacion, String fechaAutorizacion,
                               List<Map<String, String>> mensajes, String xml) {}

    public Respuesta enviar(byte[] xmlFirmado) throws Exception {
        Element r = llamar(recepcionUrl, "http://ec.gob.sri.ws.recepcion",
                "<ec:validarComprobante><xml>" + Base64.getEncoder().encodeToString(xmlFirmado) + "</xml></ec:validarComprobante>");
        return new Respuesta(texto(r, "estado"), mensajes(r));
    }

    /** Devuelve null si el SRI todavía no tiene respuesta de autorización (en proceso). */
    public Autorizacion consultar(String claveAcceso) throws Exception {
        Element r = llamar(autorizacionUrl, "http://ec.gob.sri.ws.autorizacion",
                "<ec:autorizacionComprobante><claveAccesoComprobante>" + claveAcceso + "</claveAccesoComprobante></ec:autorizacionComprobante>");
        var lista = r.getElementsByTagName("autorizacion");
        if (lista.getLength() == 0) return null;
        // Puede haber intentos previos NO AUTORIZADO; prima el AUTORIZADO.
        Element elegido = (Element) lista.item(0);
        for (int i = 0; i < lista.getLength(); i++) {
            Element a = (Element) lista.item(i);
            if ("AUTORIZADO".equals(hijo(a, "estado"))) { elegido = a; break; }
        }
        return new Autorizacion(hijo(elegido, "estado"), hijo(elegido, "numeroAutorizacion"),
                hijo(elegido, "fechaAutorizacion"), mensajes(elegido), serializar(elegido));
    }

    private Element llamar(String url, String ns, String cuerpo) throws Exception {
        String sobre = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ec=\"" + ns + "\">"
                + "<soapenv:Header/><soapenv:Body>" + cuerpo + "</soapenv:Body></soapenv:Envelope>";
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "text/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sobre, StandardCharsets.UTF_8)).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) throw new IllegalStateException("SRI respondió HTTP " + res.statusCode());
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // evita XXE
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(res.body())).getDocumentElement();
    }

    private static List<Map<String, String>> mensajes(Element e) {
        List<Map<String, String>> out = new ArrayList<>();
        var contenedores = e.getElementsByTagName("mensajes");
        for (int i = 0; i < contenedores.getLength(); i++) {
            for (Node m = contenedores.item(i).getFirstChild(); m != null; m = m.getNextSibling()) {
                if (!(m instanceof Element el)) continue;
                Map<String, String> msg = new LinkedHashMap<>();
                for (String k : List.of("identificador", "mensaje", "informacionAdicional", "tipo")) {
                    String v = hijo(el, k);
                    if (v != null) msg.put(k, v);
                }
                out.add(msg);
            }
        }
        return out;
    }

    private static String texto(Element e, String tag) {
        var l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    // Solo hijos directos: <mensaje> contiene otro <mensaje> anidado.
    private static String hijo(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element el && el.getTagName().equals(tag)) return el.getTextContent();
        return null;
    }

    public static String serializar(Node n) throws Exception {
        var t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var w = new StringWriter();
        t.transform(new DOMSource(n), new StreamResult(w));
        return w.toString();
    }
}
