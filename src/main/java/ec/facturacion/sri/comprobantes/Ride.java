package ec.facturacion.sri.comprobantes;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import ec.facturacion.sri.sri.XmlComprobante;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** RIDE (Representación Impresa del Documento Electrónico) en PDF, armado desde el XML firmado. */
@Service
public class Ride {
    private static final ZoneId ECUADOR = ZoneId.of("America/Guayaquil");
    private static final Map<String, String> NOMBRES = Map.of("01", "FACTURA", "03", "LIQUIDACIÓN DE COMPRA",
            "04", "NOTA DE CRÉDITO", "05", "NOTA DE DÉBITO", "06", "GUÍA DE REMISIÓN", "07", "COMPROBANTE DE RETENCIÓN");
    private static final Map<String, String> IMPUESTOS_RETENCION = Map.of("1", "RENTA", "2", "IVA", "6", "ISD");

    private final JdbcClient db;
    private final TemplateEngine plantillas;

    public Ride(JdbcClient db, TemplateEngine plantillas) {
        this.db = db;
        this.plantillas = plantillas;
    }

    record Fila(String estado, String xmlFirmado, String numeroAutorizacion, OffsetDateTime fechaAutorizacion,
                byte[] logo, String logoTipo) {}

    /** Columnas, filas y totales de la tabla central: cambia según el tipo de comprobante. */
    public record Tabla(List<String> columnas, List<List<String>> filas) {}

    public record Par(String nombre, String valor) {}

    public byte[] generar(long tenant, String clave) throws Exception {
        Fila f = db.sql("""
                        select c.estado, c.xml_firmado, c.numero_autorizacion, c.fecha_autorizacion, e.logo, e.logo_tipo
                        from comprobante c join emisor e on e.id = c.emisor_id
                        where c.tenant_id = ? and c.clave_acceso = ?""")
                .params(tenant, clave).query(Fila.class).optional()
                .orElseThrow(() -> new NoSuchElementException("Comprobante no encontrado"));
        if (!f.estado().equals("AUTORIZADO") && !f.estado().equals("ANULADO"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El RIDE solo existe para comprobantes autorizados (estado actual: " + f.estado() + ")");

        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element raiz = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(f.xmlFirmado().getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        Element it = hijo(raiz, "infoTributaria");
        String codDoc = t(it, "codDoc");

        Context ctx = new Context();
        ctx.setVariable("tipo", NOMBRES.get(codDoc));
        ctx.setVariable("anulado", f.estado().equals("ANULADO"));
        ctx.setVariable("razonSocial", t(it, "razonSocial"));
        ctx.setVariable("nombreComercial", t(it, "nombreComercial"));
        ctx.setVariable("ruc", t(it, "ruc"));
        ctx.setVariable("dirMatriz", t(it, "dirMatriz"));
        ctx.setVariable("agenteRetencion", t(it, "agenteRetencion"));
        ctx.setVariable("contribuyenteRimpe", t(it, "contribuyenteRimpe"));
        ctx.setVariable("numero", t(it, "estab") + "-" + t(it, "ptoEmi") + "-" + t(it, "secuencial"));
        ctx.setVariable("ambiente", "2".equals(t(it, "ambiente")) ? "PRODUCCIÓN" : "PRUEBAS");
        ctx.setVariable("claveAcceso", clave);
        ctx.setVariable("numeroAutorizacion", f.numeroAutorizacion() != null ? f.numeroAutorizacion() : clave);
        ctx.setVariable("fechaAutorizacion", f.fechaAutorizacion() == null ? null
                : f.fechaAutorizacion().atZoneSameInstant(ECUADOR).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")));
        ctx.setVariable("barras", png(new Code128Writer().encode(clave, BarcodeFormat.CODE_128, 0, 0), 1));
        ctx.setVariable("qr", png(new QRCodeWriter().encode(clave, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 0)), 4));
        if (f.logo() != null) ctx.setVariable("logo", "data:" + f.logoTipo() + ";base64," + Base64.getEncoder().encodeToString(f.logo()));

        Element info = (Element) raiz.getElementsByTagName(XmlComprobante.tipo(raiz.getTagName()).info()).item(0);
        ctx.setVariable("dirEstablecimiento", t(info, "dirEstablecimiento"));
        ctx.setVariable("contribuyenteEspecial", t(info, "contribuyenteEspecial"));
        ctx.setVariable("obligadoContabilidad", t(info, "obligadoContabilidad"));

        List<Par> receptor = new ArrayList<>();
        List<Par> totales = new ArrayList<>();
        Tabla tabla;
        switch (codDoc) {
            case "01", "03" -> {
                String quien = codDoc.equals("01") ? "Comprador" : "Proveedor";
                receptor.add(new Par("Razón social / Nombres", t(info, "razonSocial" + quien)));
                receptor.add(new Par("Identificación", t(info, "identificacion" + quien)));
                receptor.add(new Par("Dirección", t(info, "direccion" + quien)));
                receptor.add(new Par("Fecha de emisión", t(info, "fechaEmision")));
                receptor.add(new Par("Guía de remisión", t(info, "guiaRemision")));
                tabla = detalles(raiz, "codigoPrincipal");
                subtotalesIva(info, totales);
                totales.add(new Par("Descuento", t(info, "totalDescuento")));
                ivas(info, totales);
                if (codDoc.equals("01")) totales.add(new Par("Propina", t(info, "propina")));
                totales.add(new Par("VALOR TOTAL", t(info, "importeTotal")));
                ctx.setVariable("pagos", pagos(info));
            }
            case "04", "05" -> {
                receptor.add(new Par("Razón social / Nombres", t(info, "razonSocialComprador")));
                receptor.add(new Par("Identificación", t(info, "identificacionComprador")));
                receptor.add(new Par("Fecha de emisión", t(info, "fechaEmision")));
                receptor.add(new Par("Comprobante que se modifica", NOMBRES.get(t(info, "codDocModificado")) + " " + t(info, "numDocModificado")));
                receptor.add(new Par("Fecha de emisión (comprobante a modificar)", t(info, "fechaEmisionDocSustento")));
                if (codDoc.equals("04")) {
                    receptor.add(new Par("Razón de modificación", t(info, "motivo")));
                    tabla = detalles(raiz, "codigoInterno");
                    subtotalesIva(info, totales);
                    ivas(info, totales);
                    totales.add(new Par("VALOR TOTAL", t(info, "valorModificacion")));
                } else {
                    List<List<String>> filas = new ArrayList<>();
                    for (Element m : todos(raiz, "motivo")) filas.add(List.of(t(m, "razon"), t(m, "valor")));
                    tabla = new Tabla(List.of("Razón de la modificación", "Valor"), filas);
                    totales.add(new Par("Subtotal", t(info, "totalSinImpuestos")));
                    for (Element i : todos(info, "impuesto"))
                        totales.add(new Par("IVA " + t(i, "tarifa") + "%", t(i, "valor")));
                    totales.add(new Par("VALOR TOTAL", t(info, "valorTotal")));
                    ctx.setVariable("pagos", pagos(info));
                }
            }
            case "07" -> {
                receptor.add(new Par("Razón social / Nombres", t(info, "razonSocialSujetoRetenido")));
                receptor.add(new Par("Identificación", t(info, "identificacionSujetoRetenido")));
                receptor.add(new Par("Fecha de emisión", t(info, "fechaEmision")));
                receptor.add(new Par("Periodo fiscal", t(info, "periodoFiscal")));
                List<List<String>> filas = new ArrayList<>();
                BigDecimal total = BigDecimal.ZERO;
                for (Element d : todos(raiz, "docSustento")) {
                    String num = t(d, "numDocSustento");
                    String numFmt = num.length() == 15 ? num.substring(0, 3) + "-" + num.substring(3, 6) + "-" + num.substring(6) : num;
                    for (Element r : todos(d, "retencion")) {
                        filas.add(List.of(NOMBRES.getOrDefault(t(d, "codDocSustento"), t(d, "codDocSustento")), numFmt,
                                t(d, "fechaEmisionDocSustento"), t(info, "periodoFiscal"), t(r, "baseImponible"),
                                IMPUESTOS_RETENCION.getOrDefault(t(r, "codigo"), t(r, "codigo")) + " (" + t(r, "codigoRetencion") + ")",
                                t(r, "porcentajeRetener") + "%", t(r, "valorRetenido")));
                        total = total.add(new BigDecimal(t(r, "valorRetenido")));
                    }
                }
                tabla = new Tabla(List.of("Comprobante", "Número", "Fecha emisión", "Ejercicio fiscal", "Base imponible",
                        "Impuesto", "% Retención", "Valor retenido"), filas);
                totales.add(new Par("TOTAL RETENIDO", total.toPlainString()));
            }
            case "06" -> {
                receptor.add(new Par("Transportista", t(info, "razonSocialTransportista")));
                receptor.add(new Par("Identificación", t(info, "rucTransportista")));
                receptor.add(new Par("Placa", t(info, "placa")));
                receptor.add(new Par("Punto de partida", t(info, "dirPartida")));
                receptor.add(new Par("Inicio de transporte", t(info, "fechaIniTransporte")));
                receptor.add(new Par("Fin de transporte", t(info, "fechaFinTransporte")));
                List<List<String>> filas = new ArrayList<>();
                for (Element d : todos(raiz, "destinatario")) {
                    String destino = t(d, "razonSocialDestinatario") + " (" + t(d, "identificacionDestinatario") + ") - " + t(d, "dirDestinatario");
                    String sustento = t(d, "numDocSustento") == null ? "" : NOMBRES.getOrDefault(t(d, "codDocSustento"), "") + " " + t(d, "numDocSustento");
                    for (Element x : todos(d, "detalle"))
                        filas.add(List.of(destino, t(d, "motivoTraslado"), sustento, nz(t(x, "codigoInterno")), t(x, "descripcion"), t(x, "cantidad")));
                }
                tabla = new Tabla(List.of("Destinatario", "Motivo", "Doc. sustento", "Código", "Descripción", "Cantidad"), filas);
            }
            default -> throw new IllegalStateException("Tipo no soportado: " + codDoc);
        }
        ctx.setVariable("receptor", receptor.stream().filter(p -> p.valor() != null).toList());
        ctx.setVariable("tabla", tabla);
        ctx.setVariable("totales", totales.stream().filter(p -> p.valor() != null).toList());
        List<Par> adicional = new ArrayList<>();
        for (Element c : todos(raiz, "campoAdicional")) adicional.add(new Par(c.getAttribute("nombre"), c.getTextContent()));
        ctx.setVariable("adicional", adicional);

        String html = plantillas.process("ride", ctx);
        var out = new ByteArrayOutputStream();
        new PdfRendererBuilder().useFastMode().withHtmlContent(html, null).toStream(out).run();
        return out.toByteArray();
    }

    private Tabla detalles(Element raiz, String tagCodigo) {
        List<List<String>> filas = new ArrayList<>();
        for (Element d : todos(raiz, "detalle")) {
            StringBuilder desc = new StringBuilder(t(d, "descripcion"));
            for (Element a : todos(d, "detAdicional")) desc.append(" · ").append(a.getAttribute("nombre")).append(": ").append(a.getAttribute("valor"));
            filas.add(List.of(nz(t(d, tagCodigo)), desc.toString(), t(d, "cantidad"), t(d, "precioUnitario"),
                    nz(t(d, "descuento")), t(d, "precioTotalSinImpuesto")));
        }
        return new Tabla(List.of("Código", "Descripción", "Cantidad", "P. unitario", "Descuento", "Total"), filas);
    }

    /** "Subtotal 15%", "Subtotal 0%", ... por cada tarifa usada, y el subtotal sin impuestos. */
    private void subtotalesIva(Element info, List<Par> totales) {
        for (Element ti : todos(info, "totalImpuesto"))
            totales.add(new Par("Subtotal " + etiquetaIva(t(ti, "codigoPorcentaje")), t(ti, "baseImponible")));
        totales.add(new Par("Subtotal sin impuestos", t(info, "totalSinImpuestos")));
    }

    private void ivas(Element info, List<Par> totales) {
        for (Element ti : todos(info, "totalImpuesto"))
            if (new BigDecimal(t(ti, "valor")).signum() > 0)
                totales.add(new Par("IVA " + etiquetaIva(t(ti, "codigoPorcentaje")), t(ti, "valor")));
    }

    private static String etiquetaIva(String codigoPorcentaje) {
        return Catalogos.IVA.stream().filter(i -> i.codigo().equals(codigoPorcentaje)).map(Catalogos.Item::descripcion)
                .findFirst().orElse(codigoPorcentaje);
    }

    private static List<Par> pagos(Element info) {
        Map<String, String> formas = new LinkedHashMap<>();
        Catalogos.TODOS.get("formas-pago").forEach(i -> formas.put(i.codigo(), i.descripcion()));
        List<Par> out = new ArrayList<>();
        for (Element p : todos(info, "pago"))
            out.add(new Par(formas.getOrDefault(t(p, "formaPago"), t(p, "formaPago"))
                    + (t(p, "plazo") != null ? " (" + t(p, "plazo") + " " + t(p, "unidadTiempo") + ")" : ""), t(p, "total")));
        return out;
    }

    private static String png(BitMatrix m, int escala) throws Exception {
        var img = new BufferedImage(m.getWidth() * escala, m.getHeight() * escala, BufferedImage.TYPE_BYTE_BINARY);
        for (int x = 0; x < img.getWidth(); x++)
            for (int y = 0; y < img.getHeight(); y++)
                img.setRGB(x, y, m.get(x / escala, y / escala) ? 0x000000 : 0xFFFFFF);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static Element hijo(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element el && el.getTagName().equals(tag)) return el;
        return null;
    }

    /** Primer descendiente con ese nombre (null si no existe). */
    private static String t(Element e, String tag) {
        if (e == null) return null;
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private static List<Element> todos(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(l.getLength());
        for (int i = 0; i < l.getLength(); i++) out.add((Element) l.item(i));
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
