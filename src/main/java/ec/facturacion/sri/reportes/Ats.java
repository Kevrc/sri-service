package ec.facturacion.sri.reportes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Anexo Transaccional Simplificado (ATS) mensual de un emisor, para importar en DIMM Formularios.
 * <ul>
 * <li>compras: cada documento sustento de las retenciones emitidas y las liquidaciones de compra (AUTORIZADAS).</li>
 * <li>ventas: los comprobantes electrónicos no van en el detalle de ventas; se informan los totales
 *     por establecimiento (facturas + notas de débito − notas de crédito).</li>
 * <li>anulados: los comprobantes electrónicos se anulan en SRI en línea y no se repiten aquí.</li>
 * </ul>
 * Compras recibidas sin retención (facturas de proveedores) no pasan por este servicio: hay que agregarlas aparte.
 */
@Service
public class Ats {
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    // Tabla de retenciones de IVA (codigoRetencion) -> campo del ATS
    private static final Map<String, String> RET_IVA = Map.of("9", "valRetBien10", "10", "valRetServ20", "1", "valorRetBienes",
            "11", "valRetServ50", "2", "valorRetServicios", "3", "valRetServ100");
    private static final List<String> CAMPOS_RET_IVA = List.of("valRetBien10", "valRetServ20", "valorRetBienes", "valRetServ50",
            "valorRetServicios", "valRetServ100");

    private final JdbcClient db;

    public Ats(JdbcClient db) {
        this.db = db;
    }

    public record Resultado(String xml, List<String> advertencias, int compras, BigDecimal totalVentas) {}

    record Comp(String codDoc, String estab, String ptoEmi, String secuencial, String numeroAutorizacion,
                String claveAcceso, LocalDate fechaEmision, String xmlFirmado, BigDecimal subtotal) {}

    record Compra(String clave, Element detalle) {}

    public Resultado generar(long tenant, String ruc, int anio, int mes) throws Exception {
        if (mes < 1 || mes > 12 || anio < 2000 || anio > 2100) throw new IllegalArgumentException("Periodo inválido");
        record Emisor(long id, String razonSocial) {}
        Emisor emisor = db.sql("select id, razon_social from emisor where tenant_id = ? and ruc = ?").params(tenant, ruc)
                .query(Emisor.class).optional().orElseThrow(() -> new NoSuchElementException("Emisor no encontrado"));
        LocalDate desde = LocalDate.of(anio, mes, 1), hasta = desde.plusMonths(1).minusDays(1);
        List<Comp> comps = db.sql("""
                        select c.cod_doc, p.estab, p.pto_emi, c.secuencial, c.numero_autorizacion, c.clave_acceso, c.fecha_emision,
                               c.xml_firmado, c.subtotal
                        from comprobante c join punto_emision p on p.id = c.punto_emision_id
                        where c.emisor_id = ? and c.estado = 'AUTORIZADO' and c.fecha_emision between ? and ?
                        order by c.fecha_emision, c.id""")
                .params(emisor.id(), desde, hasta).query(Comp.class).list();
        List<String> estabs = db.sql("select distinct estab from punto_emision where emisor_id = ? and activo order by estab")
                .param(emisor.id()).query(String.class).list();

        List<String> avisos = new ArrayList<>();
        Document d = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element iva = d.createElement("iva");
        d.appendChild(iva);
        txt(d, iva, "TipoIDInformante", "R");
        txt(d, iva, "IdInformante", ruc);
        txt(d, iva, "razonSocial", normalizar(emisor.razonSocial()));
        txt(d, iva, "Anio", String.valueOf(anio));
        txt(d, iva, "Mes", String.format("%02d", mes));
        txt(d, iva, "numEstabRuc", String.format("%03d", Math.max(estabs.size(), 1)));

        // Ventas por establecimiento: facturas + notas de débito − notas de crédito (bases imponibles).
        Map<String, BigDecimal> ventas = new LinkedHashMap<>();
        estabs.forEach(e -> ventas.put(e, BigDecimal.ZERO));
        for (Comp c : comps) {
            int signo = switch (c.codDoc()) { case "01", "05" -> 1; case "04" -> -1; default -> 0; };
            if (signo != 0 && c.subtotal() != null) ventas.merge(c.estab(), c.subtotal().multiply(BigDecimal.valueOf(signo)), BigDecimal::add);
        }
        BigDecimal totalVentas = ventas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        txt(d, iva, "totalVentas", m(totalVentas));
        txt(d, iva, "codigoOperativo", "IVA");

        // Compras: documentos sustento de retenciones + liquidaciones de compra.
        List<Compra> compras = new ArrayList<>();
        Set<String> sustentados = new HashSet<>();
        for (Comp c : comps) if (c.codDoc().equals("07")) compras.addAll(desdeRetencion(d, c, sustentados, avisos));
        for (Comp c : comps)
            if (c.codDoc().equals("03") && !sustentados.contains("03" + c.estab() + c.ptoEmi() + c.secuencial()))
                compras.add(desdeLiquidacion(d, c, avisos));
        if (!compras.isEmpty()) {
            Element nodo = el(d, iva, "compras");
            compras.forEach(x -> nodo.appendChild(x.detalle()));
        }

        Element ve = el(d, iva, "ventasEstablecimiento");
        ventas.forEach((estab, total) -> {
            Element v = el(d, ve, "ventaEst");
            txt(d, v, "codEstab", estab);
            txt(d, v, "ventasEstab", m(total.max(BigDecimal.ZERO)));
            txt(d, v, "ivaComp", "0.00");
        });
        if (totalVentas.signum() < 0) avisos.add("Las notas de crédito superan a las ventas del mes: ventasEstab se reporta en 0.00.");

        long anulados = db.sql("select count(*) from comprobante where emisor_id = ? and estado = 'ANULADO' and fecha_emision between ? and ?")
                .params(emisor.id(), desde, hasta).query(Long.class).single();
        if (anulados > 0)
            avisos.add(anulados + " comprobante(s) electrónico(s) anulado(s) en el mes: no van en el ATS, pero verifica que estén anulados en SRI en línea.");
        avisos.add("Las facturas de proveedores sin retención no pasan por este servicio: agrégalas en DIMM si corresponde.");

        var t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
        var w = new java.io.StringWriter();
        t.transform(new javax.xml.transform.dom.DOMSource(d), new javax.xml.transform.stream.StreamResult(w));
        return new Resultado(w.toString(), avisos, compras.size(), totalVentas);
    }

    private List<Compra> desdeRetencion(Document d, Comp ret, Set<String> sustentados, List<String> avisos) throws Exception {
        Element raiz = parse(ret.xmlFirmado());
        Element info = (Element) raiz.getElementsByTagName("infoCompRetencion").item(0);
        String tipoId = t(info, "tipoIdentificacionSujetoRetenido");
        List<Compra> out = new ArrayList<>();
        for (Element doc : todos(raiz, "docSustento")) {
            String num = t(doc, "numDocSustento");
            sustentados.add(t(doc, "codDocSustento") + num);
            Element det = d.createElement("detalleCompras");
            txt(d, det, "codSustento", t(doc, "codSustento"));
            proveedor(d, det, tipoId, t(info, "identificacionSujetoRetenido"), t(info, "razonSocialSujetoRetenido"),
                    t(info, "tipoSujetoRetenido"), t(doc, "codDocSustento"), "SI".equals(t(info, "parteRel")));
            txt(d, det, "fechaRegistro", ret.fechaEmision().format(DDMMYYYY));
            txt(d, det, "establecimiento", num.substring(0, 3));
            txt(d, det, "puntoEmision", num.substring(3, 6));
            txt(d, det, "secuencial", num.substring(6));
            txt(d, det, "fechaEmision", t(doc, "fechaEmisionDocSustento"));
            String aut = t(doc, "numAutDocSustento");
            if (aut == null) {
                aut = "0000000000";
                avisos.add("Retención " + ret.estab() + "-" + ret.ptoEmi() + "-" + ret.secuencial() + ": el documento " + num
                        + " no tiene número de autorización; complétalo en DIMM.");
            }
            txt(d, det, "autorizacion", aut);
            bases(d, det, todos(doc, "impuestoDocSustento"), "valorImpuesto");

            Map<String, BigDecimal> retIva = new LinkedHashMap<>();
            CAMPOS_RET_IVA.forEach(c -> retIva.put(c, BigDecimal.ZERO));
            List<Element> renta = new ArrayList<>();
            for (Element r : todos(doc, "retencion")) {
                if ("1".equals(t(r, "codigo"))) renta.add(r);
                else if ("2".equals(t(r, "codigo"))) {
                    String campo = RET_IVA.get(t(r, "codigoRetencion"));
                    if (campo != null) retIva.merge(campo, n(t(r, "valorRetenido")), BigDecimal::add);
                }
            }
            retIva.forEach((k, v) -> txt(d, det, k, m(v)));
            txt(d, det, "valorRetencionNc", "0.00");
            txt(d, det, "totbasesImpReemb", "0.00");
            pagoLocal(d, det);
            formasPago(d, det, todos(doc, "pago"));
            if (!renta.isEmpty()) {
                Element air = el(d, det, "air");
                for (Element r : renta) {
                    Element x = el(d, air, "detalleAir");
                    txt(d, x, "codRetAir", t(r, "codigoRetencion"));
                    txt(d, x, "baseImpAir", m(n(t(r, "baseImponible"))));
                    txt(d, x, "porcentajeAir", t(r, "porcentajeRetener"));
                    txt(d, x, "valRetAir", m(n(t(r, "valorRetenido"))));
                }
            }
            txt(d, det, "estabRetencion1", ret.estab());
            txt(d, det, "ptoEmiRetencion1", ret.ptoEmi());
            txt(d, det, "secRetencion1", ret.secuencial());
            txt(d, det, "autRetencion1", ret.numeroAutorizacion() != null ? ret.numeroAutorizacion() : ret.claveAcceso());
            txt(d, det, "fechaEmiRet1", ret.fechaEmision().format(DDMMYYYY));
            out.add(new Compra(ret.claveAcceso(), det));
        }
        return out;
    }

    private Compra desdeLiquidacion(Document d, Comp liq, List<String> avisos) throws Exception {
        Element raiz = parse(liq.xmlFirmado());
        Element info = (Element) raiz.getElementsByTagName("infoLiquidacionCompra").item(0);
        Element det = d.createElement("detalleCompras");
        // La liquidación no dice el sustento tributario: 01 (crédito tributario de IVA) es el caso más común.
        txt(d, det, "codSustento", "01");
        avisos.add("Liquidación " + liq.estab() + "-" + liq.ptoEmi() + "-" + liq.secuencial()
                + ": se usó sustento tributario 01; cámbialo en DIMM si corresponde otro.");
        proveedor(d, det, t(info, "tipoIdentificacionProveedor"), t(info, "identificacionProveedor"),
                t(info, "razonSocialProveedor"), null, "03", false);
        txt(d, det, "fechaRegistro", liq.fechaEmision().format(DDMMYYYY));
        txt(d, det, "establecimiento", liq.estab());
        txt(d, det, "puntoEmision", liq.ptoEmi());
        txt(d, det, "secuencial", liq.secuencial());
        txt(d, det, "fechaEmision", liq.fechaEmision().format(DDMMYYYY));
        txt(d, det, "autorizacion", liq.numeroAutorizacion() != null ? liq.numeroAutorizacion() : liq.claveAcceso());
        bases(d, det, todos(info, "totalImpuesto"), "valor");
        CAMPOS_RET_IVA.forEach(c -> txt(d, det, c, "0.00"));
        txt(d, det, "valorRetencionNc", "0.00");
        txt(d, det, "totbasesImpReemb", "0.00");
        pagoLocal(d, det);
        formasPago(d, det, todos(info, "pago"));
        return new Compra(liq.claveAcceso(), det);
    }

    /** tpIdProv del ATS: 01 RUC, 02 cédula, 03 pasaporte / exterior. */
    private void proveedor(Document d, Element det, String tipoId, String id, String razon, String tipoSujeto, String tipoComprobante, boolean parteRel) {
        String tp = switch (tipoId) { case "04" -> "01"; case "05" -> "02"; default -> "03"; };
        txt(d, det, "tpIdProv", tp);
        txt(d, det, "idProv", id);
        txt(d, det, "tipoComprobante", tipoComprobante);
        txt(d, det, "parteRel", parteRel ? "SI" : "NO");
        if (tp.equals("03")) {
            txt(d, det, "tipoProv", tipoSujeto != null ? tipoSujeto : "01");
            txt(d, det, "denoProv", normalizar(razon));
        }
    }

    /** Bases por tarifa: 6 = no objeto, 0 = 0%, 7 = exento, el resto gravado; más ICE e IVA. */
    private void bases(Document d, Element det, List<Element> impuestos, String campoValor) {
        BigDecimal noObjeto = BigDecimal.ZERO, cero = BigDecimal.ZERO, gravada = BigDecimal.ZERO, exenta = BigDecimal.ZERO, iva = BigDecimal.ZERO;
        for (Element i : impuestos) {
            BigDecimal base = n(t(i, "baseImponible"));
            switch (t(i, "codigoPorcentaje")) {
                case "6" -> noObjeto = noObjeto.add(base);
                case "0" -> cero = cero.add(base);
                case "7" -> exenta = exenta.add(base);
                default -> gravada = gravada.add(base);
            }
            iva = iva.add(n(t(i, campoValor)));
        }
        txt(d, det, "baseNoGraIva", m(noObjeto));
        txt(d, det, "baseImponible", m(cero));
        txt(d, det, "baseImpGrav", m(gravada));
        txt(d, det, "baseImpExe", m(exenta));
        txt(d, det, "montoIce", "0.00");
        txt(d, det, "montoIva", m(iva));
    }

    private void pagoLocal(Document d, Element det) {
        Element p = el(d, det, "pagoExterior");
        txt(d, p, "pagoLocExt", "01");
        txt(d, p, "paisEfecPago", "NA");
        txt(d, p, "aplicConvDobTrib", "NA");
        txt(d, p, "pagExtSujRetNorLeg", "NA");
    }

    private void formasPago(Document d, Element det, List<Element> pagos) {
        Set<String> formas = new LinkedHashSet<>();
        for (Element p : pagos) formas.add(t(p, "formaPago"));
        if (formas.isEmpty()) return;
        Element f = el(d, det, "formasDePago");
        formas.forEach(x -> txt(d, f, "formaPago", x));
    }

    /** DIMM rechaza tildes y símbolos: mayúsculas sin acentos, solo letras, números y espacios. */
    public static String normalizar(String s) {
        if (s == null) return "";
        String sin = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sin.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Element parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private static List<Element> todos(Element e, String tag) {
        var l = e.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>(l.getLength());
        for (int i = 0; i < l.getLength(); i++) out.add((Element) l.item(i));
        return out;
    }

    private static String t(Element e, String tag) {
        var l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private static BigDecimal n(String s) {
        return s == null ? BigDecimal.ZERO : new BigDecimal(s);
    }

    private static String m(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static Element el(Document d, Element padre, String nombre) {
        Element e = d.createElement(nombre);
        padre.appendChild(e);
        return e;
    }

    private static void txt(Document d, Element padre, String nombre, String valor) {
        el(d, padre, nombre).setTextContent(valor);
    }
}
