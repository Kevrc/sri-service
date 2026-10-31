package ec.facturacion.sri.comprobantes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON simple por tipo de comprobante -> contenido en la estructura y orden del XSD del SRI.
 * Calcula subtotales, IVA y totales; completa los datos del emisor (dirección del establecimiento,
 * contribuyente especial, obligado a llevar contabilidad) desde la base.
 */
@Component
public class Simplificado {
    private static final ZoneId ECUADOR = ZoneId.of("America/Guayaquil");
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal CIEN = new BigDecimal("100");

    private final JdbcClient db;
    private final JsonMapper json;

    public Simplificado(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---------- Piezas comunes ----------

    public record Persona(@NotBlank @Pattern(regexp = "04|05|06|07|08") String tipoIdentificacion,
                          @NotBlank @Size(max = 20) String identificacion, @NotBlank @Size(max = 300) String razonSocial,
                          @Size(max = 300) String direccion, @Email String email) {}

    public record Item(@Size(max = 25) String codigo, @Size(max = 25) String codigoAuxiliar,
                       @NotBlank @Size(max = 300) String descripcion,
                       @NotNull @Positive BigDecimal cantidad, @NotNull @PositiveOrZero BigDecimal precioUnitario,
                       @PositiveOrZero BigDecimal descuento, @NotBlank String iva,
                       @Size(max = 3) Map<String, String> detallesAdicionales) {}

    public record Pago(@NotBlank @Pattern(regexp = "\\d{2}") String formaPago, @Positive BigDecimal total,
                       @Positive Integer plazo, String unidadTiempo) {}

    /** Documento al que se refiere una nota de crédito/débito o que sustenta una guía. */
    public record Documento(@Pattern(regexp = "\\d{2}") String codDoc,
                            @NotBlank @Pattern(regexp = "\\d{3}-\\d{3}-\\d{9}") String numero,
                            @NotNull LocalDate fechaEmision, String numAutorizacion) {}

    // ---------- Comprobantes ----------

    public record Factura(@Pattern(regexp = "\\d{13}") String ruc, @Pattern(regexp = "\\d{3}") String establecimiento,
                          @Pattern(regexp = "\\d{3}") String puntoEmision, LocalDate fechaEmision,
                          @Valid @NotNull Persona comprador, @NotEmpty List<@Valid Item> items,
                          @NotEmpty List<@Valid Pago> pagos, @PositiveOrZero BigDecimal propina,
                          @Pattern(regexp = "\\d{3}-\\d{3}-\\d{9}") String guiaRemision, Map<String, String> infoAdicional) {}

    public record NotaCredito(@Pattern(regexp = "\\d{13}") String ruc, @Pattern(regexp = "\\d{3}") String establecimiento,
                              @Pattern(regexp = "\\d{3}") String puntoEmision, LocalDate fechaEmision,
                              @Valid @NotNull Persona comprador, @Valid @NotNull Documento docModificado,
                              @NotBlank @Size(max = 300) String motivo, @NotEmpty List<@Valid Item> items,
                              Map<String, String> infoAdicional) {}

    public record Motivo(@NotBlank @Size(max = 300) String razon, @NotNull @Positive BigDecimal valor) {}

    public record NotaDebito(@Pattern(regexp = "\\d{13}") String ruc, @Pattern(regexp = "\\d{3}") String establecimiento,
                             @Pattern(regexp = "\\d{3}") String puntoEmision, LocalDate fechaEmision,
                             @Valid @NotNull Persona comprador, @Valid @NotNull Documento docModificado,
                             @NotEmpty List<@Valid Motivo> motivos, @NotBlank String iva, List<@Valid Pago> pagos,
                             Map<String, String> infoAdicional) {}

    public record SujetoRetenido(@NotBlank @Pattern(regexp = "04|05|06|08") String tipoIdentificacion,
                                 @NotBlank @Size(max = 20) String identificacion, @NotBlank @Size(max = 300) String razonSocial,
                                 Boolean parteRelacionada, @Pattern(regexp = "01|02") String tipoSujeto, @Email String email) {}

    public record ImpuestoSustento(@NotBlank String iva, @NotNull @PositiveOrZero BigDecimal baseImponible) {}

    /** codigo: 1 = renta, 2 = IVA, 6 = ISD. porcentaje: el vigente para codigoRetencion (ver /catalogos). */
    public record Retencion(@NotBlank @Pattern(regexp = "1|2|6") String codigo, @NotBlank String codigoRetencion,
                            @NotNull @PositiveOrZero BigDecimal baseImponible, @NotNull @PositiveOrZero BigDecimal porcentaje) {}

    public record DocSustento(@NotBlank @Pattern(regexp = "\\d{2}") String codSustento,
                              @NotBlank @Pattern(regexp = "\\d{2}") String codDoc,
                              @NotBlank @Pattern(regexp = "\\d{3}-?\\d{3}-?\\d{9}") String numero,
                              @NotNull LocalDate fechaEmision, String numAutorizacion,
                              @NotEmpty List<@Valid ImpuestoSustento> impuestos, @NotEmpty List<@Valid Retencion> retenciones,
                              @NotEmpty List<@Valid Pago> pagos) {}

    public record ComprobanteRetencion(@Pattern(regexp = "\\d{13}") String ruc, @Pattern(regexp = "\\d{3}") String establecimiento,
                                       @Pattern(regexp = "\\d{3}") String puntoEmision, LocalDate fechaEmision,
                                       @Valid @NotNull SujetoRetenido sujetoRetenido,
                                       @Pattern(regexp = "\\d{2}/\\d{4}") String periodoFiscal,
                                       @NotEmpty List<@Valid DocSustento> docsSustento, Map<String, String> infoAdicional) {}

    public record ItemGuia(@Size(max = 25) String codigo, @NotBlank @Size(max = 300) String descripcion,
                           @NotNull @Positive BigDecimal cantidad) {}

    public record Destinatario(@NotBlank @Size(max = 20) String identificacion, @NotBlank String razonSocial,
                               @NotBlank String direccion, @NotBlank @Size(max = 300) String motivoTraslado,
                               @Pattern(regexp = "\\d{3}") String codEstabDestino, @Size(max = 300) String ruta,
                               @Valid Documento docSustento, @NotEmpty List<@Valid ItemGuia> items) {}

    public record Transportista(@NotBlank @Pattern(regexp = "04|05|06|07|08") String tipoIdentificacion,
                                @NotBlank @Size(max = 20) String identificacion, @NotBlank String razonSocial) {}

    public record GuiaRemision(@Pattern(regexp = "\\d{13}") String ruc, @Pattern(regexp = "\\d{3}") String establecimiento,
                               @Pattern(regexp = "\\d{3}") String puntoEmision,
                               @Valid @NotNull Transportista transportista, @NotBlank @Size(max = 20) String placa,
                               @NotBlank @Size(max = 300) String dirPartida,
                               @NotNull LocalDate fechaInicioTransporte, @NotNull LocalDate fechaFinTransporte,
                               @NotEmpty List<@Valid Destinatario> destinatarios, Map<String, String> infoAdicional) {}

    public record LiquidacionCompra(@Pattern(regexp = "\\d{13}") String ruc, @Pattern(regexp = "\\d{3}") String establecimiento,
                                    @Pattern(regexp = "\\d{3}") String puntoEmision, LocalDate fechaEmision,
                                    @Valid @NotNull Persona proveedor, @NotEmpty List<@Valid Item> items,
                                    @NotEmpty List<@Valid Pago> pagos, Map<String, String> infoAdicional) {}

    // ---------- Traducción ----------

    record DatosEmisor(String dirEstablecimiento, String contribuyenteEspecial, boolean obligadoContabilidad) {}

    private DatosEmisor emisor(long tenant, String ruc, String estab, String pto) {
        return db.sql("""
                        select p.dir_establecimiento, e.contribuyente_especial, e.obligado_contabilidad
                        from emisor e join punto_emision p on p.emisor_id = e.id
                        where e.tenant_id = ? and e.ruc = ? and p.estab = ? and p.pto_emi = ? and p.activo""")
                .params(tenant, ruc, estab, pto).query(DatosEmisor.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Emisor o punto de emisión no registrado: " + ruc + " " + estab + "-" + pto));
    }

    /** Cabecera común: fechaEmision, dirEstablecimiento y, según el tipo, contribuyenteEspecial/obligadoContabilidad. */
    private ObjectNode info(LocalDate fecha, DatosEmisor e) {
        return json.createObjectNode().put("fechaEmision", fecha(fecha)).put("dirEstablecimiento", e.dirEstablecimiento());
    }

    private void contribuyente(ObjectNode n, DatosEmisor e) {
        if (e.contribuyenteEspecial() != null) n.put("contribuyenteEspecial", e.contribuyenteEspecial());
        n.put("obligadoContabilidad", e.obligadoContabilidad() ? "SI" : "NO");
    }

    public EmisionService.Solicitud factura(long tenant, Factura f) {
        DatosEmisor e = emisor(tenant, f.ruc(), f.establecimiento(), f.puntoEmision());
        var lineas = lineas(f.items());
        BigDecimal sinImpuestos = suma(lineas, Linea::base), descuento = suma(lineas, Linea::descuento);
        var totales = totales(lineas);
        BigDecimal propina = r2(nz(f.propina()));
        BigDecimal total = sinImpuestos.add(suma(totales, Total::valor)).add(propina);

        ObjectNode info = info(f.fechaEmision(), e);
        contribuyente(info, e);
        info.put("tipoIdentificacionComprador", f.comprador().tipoIdentificacion());
        if (f.guiaRemision() != null) info.put("guiaRemision", f.guiaRemision());
        info.put("razonSocialComprador", f.comprador().razonSocial())
                .put("identificacionComprador", f.comprador().identificacion());
        if (f.comprador().direccion() != null) info.put("direccionComprador", f.comprador().direccion());
        info.put("totalSinImpuestos", sinImpuestos).put("totalDescuento", descuento);
        info.set("totalConImpuestos", totalConImpuestos(totales));
        info.put("propina", propina).put("importeTotal", total).put("moneda", "DOLAR");
        info.set("pagos", pagos(f.pagos(), total));

        ObjectNode c = json.createObjectNode().set("infoFactura", info);
        c.set("detalles", detalles(lineas, "codigoPrincipal", "codigoAuxiliar"));
        adicional(c, f.infoAdicional(), f.comprador().email());
        return new EmisionService.Solicitud(f.ruc(), f.establecimiento(), f.puntoEmision(), "factura", "1.1.0", c);
    }

    public EmisionService.Solicitud notaCredito(long tenant, NotaCredito n) {
        DatosEmisor e = emisor(tenant, n.ruc(), n.establecimiento(), n.puntoEmision());
        var lineas = lineas(n.items());
        BigDecimal sinImpuestos = suma(lineas, Linea::base);
        var totales = totales(lineas);

        ObjectNode info = info(n.fechaEmision(), e)
                .put("tipoIdentificacionComprador", n.comprador().tipoIdentificacion())
                .put("razonSocialComprador", n.comprador().razonSocial())
                .put("identificacionComprador", n.comprador().identificacion());
        contribuyente(info, e);
        modificado(info, n.docModificado());
        info.put("totalSinImpuestos", sinImpuestos)
                .put("valorModificacion", sinImpuestos.add(suma(totales, Total::valor))).put("moneda", "DOLAR");
        info.set("totalConImpuestos", totalConImpuestos(totales));
        info.put("motivo", n.motivo());

        ObjectNode c = json.createObjectNode().set("infoNotaCredito", info);
        c.set("detalles", detalles(lineas, "codigoInterno", "codigoAdicional"));
        adicional(c, n.infoAdicional(), n.comprador().email());
        return new EmisionService.Solicitud(n.ruc(), n.establecimiento(), n.puntoEmision(), "notaCredito", "1.1.0", c);
    }

    public EmisionService.Solicitud notaDebito(long tenant, NotaDebito n) {
        DatosEmisor e = emisor(tenant, n.ruc(), n.establecimiento(), n.puntoEmision());
        BigDecimal base = r2(n.motivos().stream().map(Motivo::valor).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tarifa = Catalogos.tarifaIva(n.iva());
        BigDecimal iva = r2(base.multiply(tarifa).divide(CIEN));
        BigDecimal total = base.add(iva);

        ObjectNode info = info(n.fechaEmision(), e)
                .put("tipoIdentificacionComprador", n.comprador().tipoIdentificacion())
                .put("razonSocialComprador", n.comprador().razonSocial())
                .put("identificacionComprador", n.comprador().identificacion());
        contribuyente(info, e);
        modificado(info, n.docModificado());
        info.put("totalSinImpuestos", base);
        info.putObject("impuestos").putArray("impuesto").addObject().put("codigo", "2").put("codigoPorcentaje", n.iva())
                .put("tarifa", tarifa).put("baseImponible", base).put("valor", iva);
        info.put("valorTotal", total);
        if (n.pagos() != null && !n.pagos().isEmpty()) info.set("pagos", pagos(n.pagos(), total));

        ObjectNode c = json.createObjectNode().set("infoNotaDebito", info);
        ArrayNode motivos = c.putObject("motivos").putArray("motivo");
        n.motivos().forEach(m -> motivos.addObject().put("razon", m.razon()).put("valor", r2(m.valor())));
        adicional(c, n.infoAdicional(), n.comprador().email());
        return new EmisionService.Solicitud(n.ruc(), n.establecimiento(), n.puntoEmision(), "notaDebito", "1.0.0", c);
    }

    public EmisionService.Solicitud retencion(long tenant, ComprobanteRetencion r) {
        DatosEmisor e = emisor(tenant, r.ruc(), r.establecimiento(), r.puntoEmision());
        LocalDate fecha = hoySiNull(r.fechaEmision());
        var s = r.sujetoRetenido();
        if (s.tipoIdentificacion().equals("08") && s.tipoSujeto() == null)
            throw new IllegalArgumentException("tipoSujeto (01 persona natural, 02 sociedad) es obligatorio con identificación del exterior");

        ObjectNode info = info(fecha, e);
        contribuyente(info, e);
        info.put("tipoIdentificacionSujetoRetenido", s.tipoIdentificacion());
        if (s.tipoIdentificacion().equals("08")) info.put("tipoSujetoRetenido", s.tipoSujeto());
        info.put("parteRel", Boolean.TRUE.equals(s.parteRelacionada()) ? "SI" : "NO")
                .put("razonSocialSujetoRetenido", s.razonSocial())
                .put("identificacionSujetoRetenido", s.identificacion())
                .put("periodoFiscal", r.periodoFiscal() != null ? r.periodoFiscal() : fecha.format(DateTimeFormatter.ofPattern("MM/yyyy")));

        ObjectNode c = json.createObjectNode().set("infoCompRetencion", info);
        ArrayNode docs = c.putObject("docsSustento").putArray("docSustento");
        for (DocSustento d : r.docsSustento()) {
            BigDecimal sinImpuestos = BigDecimal.ZERO, impuestos = BigDecimal.ZERO;
            ArrayNode imps = json.createArrayNode();
            for (ImpuestoSustento i : d.impuestos()) {
                BigDecimal tarifa = Catalogos.tarifaIva(i.iva()), base = r2(i.baseImponible());
                BigDecimal valor = r2(base.multiply(tarifa).divide(CIEN));
                sinImpuestos = sinImpuestos.add(base);
                impuestos = impuestos.add(valor);
                imps.addObject().put("codImpuestoDocSustento", "2").put("codigoPorcentaje", i.iva())
                        .put("baseImponible", base).put("tarifa", tarifa).put("valorImpuesto", valor);
            }
            BigDecimal total = sinImpuestos.add(impuestos);
            ObjectNode doc = docs.addObject().put("codSustento", d.codSustento()).put("codDocSustento", d.codDoc())
                    .put("numDocSustento", d.numero().replace("-", ""))
                    .put("fechaEmisionDocSustento", fecha(d.fechaEmision()));
            if (d.numAutorizacion() != null) doc.put("numAutDocSustento", d.numAutorizacion());
            // shortcut: solo pagos locales (01); pagos al exterior requieren tipoRegi, paisEfecPago, etc.
            doc.put("pagoLocExt", "01").put("totalSinImpuestos", sinImpuestos).put("importeTotal", total);
            doc.putObject("impuestosDocSustento").set("impuestoDocSustento", imps);
            ArrayNode rets = doc.putObject("retenciones").putArray("retencion");
            for (Retencion x : d.retenciones()) {
                BigDecimal base = r2(x.baseImponible());
                rets.addObject().put("codigo", x.codigo()).put("codigoRetencion", x.codigoRetencion())
                        .put("baseImponible", base).put("porcentajeRetener", x.porcentaje().stripTrailingZeros().toPlainString())
                        .put("valorRetenido", r2(base.multiply(x.porcentaje()).divide(CIEN)));
            }
            ArrayNode pagos = doc.putObject("pagos").putArray("pago");
            for (ObjectNode p : pagosLista(d.pagos(), total)) pagos.addObject().put("formaPago", p.get("formaPago").asString()).set("total", p.get("total"));
        }
        adicional(c, r.infoAdicional(), s.email());
        return new EmisionService.Solicitud(r.ruc(), r.establecimiento(), r.puntoEmision(), "comprobanteRetencion", "2.0.0", c);
    }

    public EmisionService.Solicitud guiaRemision(long tenant, GuiaRemision g) {
        DatosEmisor e = emisor(tenant, g.ruc(), g.establecimiento(), g.puntoEmision());
        if (g.fechaFinTransporte().isBefore(g.fechaInicioTransporte()))
            throw new IllegalArgumentException("fechaFinTransporte no puede ser anterior a fechaInicioTransporte");

        ObjectNode info = json.createObjectNode().put("dirEstablecimiento", e.dirEstablecimiento())
                .put("dirPartida", g.dirPartida())
                .put("razonSocialTransportista", g.transportista().razonSocial())
                .put("tipoIdentificacionTransportista", g.transportista().tipoIdentificacion())
                .put("rucTransportista", g.transportista().identificacion())
                .put("obligadoContabilidad", e.obligadoContabilidad() ? "SI" : "NO");
        if (e.contribuyenteEspecial() != null) info.put("contribuyenteEspecial", e.contribuyenteEspecial());
        info.put("fechaIniTransporte", fecha(g.fechaInicioTransporte()))
                .put("fechaFinTransporte", fecha(g.fechaFinTransporte())).put("placa", g.placa());

        ObjectNode c = json.createObjectNode().set("infoGuiaRemision", info);
        ArrayNode dest = c.putObject("destinatarios").putArray("destinatario");
        for (Destinatario d : g.destinatarios()) {
            ObjectNode n = dest.addObject().put("identificacionDestinatario", d.identificacion())
                    .put("razonSocialDestinatario", d.razonSocial()).put("dirDestinatario", d.direccion())
                    .put("motivoTraslado", d.motivoTraslado());
            if (d.codEstabDestino() != null) n.put("codEstabDestino", d.codEstabDestino());
            if (d.ruta() != null) n.put("ruta", d.ruta());
            if (d.docSustento() != null) {
                Documento s = d.docSustento();
                n.put("codDocSustento", s.codDoc() == null ? "01" : s.codDoc()).put("numDocSustento", s.numero());
                if (s.numAutorizacion() != null) n.put("numAutDocSustento", s.numAutorizacion());
                n.put("fechaEmisionDocSustento", fecha(s.fechaEmision()));
            }
            ArrayNode det = n.putObject("detalles").putArray("detalle");
            for (ItemGuia i : d.items()) {
                ObjectNode x = det.addObject();
                if (i.codigo() != null) x.put("codigoInterno", i.codigo());
                x.put("descripcion", i.descripcion()).put("cantidad", seis(i.cantidad()));
            }
        }
        adicional(c, g.infoAdicional(), null);
        return new EmisionService.Solicitud(g.ruc(), g.establecimiento(), g.puntoEmision(), "guiaRemision", "1.1.0", c);
    }

    public EmisionService.Solicitud liquidacionCompra(long tenant, LiquidacionCompra l) {
        DatosEmisor e = emisor(tenant, l.ruc(), l.establecimiento(), l.puntoEmision());
        var lineas = lineas(l.items());
        BigDecimal sinImpuestos = suma(lineas, Linea::base), descuento = suma(lineas, Linea::descuento);
        var totales = totales(lineas);
        BigDecimal total = sinImpuestos.add(suma(totales, Total::valor));

        ObjectNode info = info(l.fechaEmision(), e);
        contribuyente(info, e);
        info.put("tipoIdentificacionProveedor", l.proveedor().tipoIdentificacion())
                .put("razonSocialProveedor", l.proveedor().razonSocial())
                .put("identificacionProveedor", l.proveedor().identificacion());
        if (l.proveedor().direccion() != null) info.put("direccionProveedor", l.proveedor().direccion());
        info.put("totalSinImpuestos", sinImpuestos).put("totalDescuento", descuento);
        info.set("totalConImpuestos", totalConImpuestos(totales));
        info.put("importeTotal", total).put("moneda", "DOLAR");
        info.set("pagos", pagos(l.pagos(), total));

        ObjectNode c = json.createObjectNode().set("infoLiquidacionCompra", info);
        c.set("detalles", detalles(lineas, "codigoPrincipal", "codigoAuxiliar"));
        adicional(c, l.infoAdicional(), l.proveedor().email());
        return new EmisionService.Solicitud(l.ruc(), l.establecimiento(), l.puntoEmision(), "liquidacionCompra", "1.1.0", c);
    }

    // ---------- Cálculos ----------

    record Linea(Item item, BigDecimal descuento, BigDecimal base, BigDecimal tarifa, BigDecimal iva) {}

    record Total(String codigoPorcentaje, BigDecimal base, BigDecimal valor) {}

    /** Subtotal por línea = cantidad x precio - descuento, a 2 decimales; IVA por línea sobre ese subtotal. */
    static List<Linea> lineas(List<Item> items) {
        List<Linea> out = new ArrayList<>();
        for (Item i : items) {
            BigDecimal descuento = r2(nz(i.descuento()));
            BigDecimal base = r2(i.cantidad().multiply(i.precioUnitario()).subtract(descuento));
            if (base.signum() < 0) throw new IllegalArgumentException("El descuento supera el subtotal en: " + i.descripcion());
            BigDecimal tarifa = Catalogos.tarifaIva(i.iva());
            out.add(new Linea(i, descuento, base, tarifa, r2(base.multiply(tarifa).divide(CIEN))));
        }
        return out;
    }

    /** Un total por tarifa de IVA: base sumada y el impuesto calculado sobre esa base. */
    static List<Total> totales(List<Linea> lineas) {
        Map<String, BigDecimal> bases = new LinkedHashMap<>();
        Map<String, BigDecimal> tarifas = new LinkedHashMap<>();
        for (Linea l : lineas) {
            bases.merge(l.item().iva(), l.base(), BigDecimal::add);
            tarifas.put(l.item().iva(), l.tarifa());
        }
        List<Total> out = new ArrayList<>();
        bases.forEach((cod, base) -> out.add(new Total(cod, base, r2(base.multiply(tarifas.get(cod)).divide(CIEN)))));
        return out;
    }

    private ObjectNode totalConImpuestos(List<Total> totales) {
        ObjectNode n = json.createObjectNode();
        ArrayNode a = n.putArray("totalImpuesto");
        totales.forEach(t -> a.addObject().put("codigo", "2").put("codigoPorcentaje", t.codigoPorcentaje())
                .put("baseImponible", t.base()).put("valor", t.valor()));
        return n;
    }

    private ObjectNode detalles(List<Linea> lineas, String tagCodigo, String tagAuxiliar) {
        ObjectNode n = json.createObjectNode();
        ArrayNode a = n.putArray("detalle");
        for (Linea l : lineas) {
            Item i = l.item();
            ObjectNode d = a.addObject();
            if (i.codigo() != null) d.put(tagCodigo, i.codigo());
            if (i.codigoAuxiliar() != null) d.put(tagAuxiliar, i.codigoAuxiliar());
            d.put("descripcion", i.descripcion()).put("cantidad", seis(i.cantidad()))
                    .put("precioUnitario", seis(i.precioUnitario())).put("descuento", l.descuento())
                    .put("precioTotalSinImpuesto", l.base());
            if (i.detallesAdicionales() != null && !i.detallesAdicionales().isEmpty()) {
                ArrayNode det = d.putObject("detallesAdicionales").putArray("detAdicional");
                i.detallesAdicionales().forEach((k, v) -> det.addObject().put("@nombre", k).put("@valor", v));
            }
            d.putObject("impuestos").putArray("impuesto").addObject().put("codigo", "2").put("codigoPorcentaje", i.iva())
                    .put("tarifa", l.tarifa()).put("baseImponible", l.base()).put("valor", l.iva());
        }
        return n;
    }

    private void modificado(ObjectNode info, Documento d) {
        info.put("codDocModificado", d.codDoc() == null ? "01" : d.codDoc()).put("numDocModificado", d.numero())
                .put("fechaEmisionDocSustento", fecha(d.fechaEmision()));
    }

    /** Un solo pago sin total toma el importe total; con varios, deben sumar exactamente el total. */
    private List<ObjectNode> pagosLista(List<Pago> pagos, BigDecimal total) {
        List<ObjectNode> out = new ArrayList<>();
        BigDecimal suma = BigDecimal.ZERO;
        for (Pago p : pagos) {
            BigDecimal t = p.total() == null && pagos.size() == 1 ? total : p.total();
            if (t == null) throw new IllegalArgumentException("Con varios pagos, cada uno debe indicar su total");
            t = r2(t);
            suma = suma.add(t);
            ObjectNode n = json.createObjectNode().put("formaPago", p.formaPago()).put("total", t);
            if (p.plazo() != null) n.put("plazo", p.plazo()).put("unidadTiempo", p.unidadTiempo() == null ? "dias" : p.unidadTiempo());
            out.add(n);
        }
        if (suma.compareTo(total) != 0) throw new IllegalArgumentException("Los pagos suman " + suma + " pero el total es " + total);
        return out;
    }

    private ObjectNode pagos(List<Pago> pagos, BigDecimal total) {
        ObjectNode n = json.createObjectNode();
        ArrayNode a = n.putArray("pago");
        pagosLista(pagos, total).forEach(a::add);
        return n;
    }

    /** infoAdicional: el email del cliente va como "Email" (lo usan para enviarle el comprobante). Máximo 15 campos. */
    private void adicional(ObjectNode c, Map<String, String> extra, String email) {
        Map<String, String> campos = new LinkedHashMap<>();
        if (email != null && !email.isBlank()) campos.put("Email", email);
        if (extra != null) extra.forEach((k, v) -> { if (k != null && v != null && !v.isBlank()) campos.put(k, v); });
        if (campos.isEmpty()) return;
        if (campos.size() > 15) throw new IllegalArgumentException("infoAdicional admite máximo 15 campos");
        ArrayNode a = c.putObject("infoAdicional").putArray("campoAdicional");
        campos.forEach((k, v) -> {
            if (v.length() > 300) throw new IllegalArgumentException("infoAdicional." + k + " supera 300 caracteres");
            a.addObject().put("@nombre", k).put("#text", v);
        });
    }

    private static String fecha(LocalDate f) {
        return hoySiNull(f).format(DDMMYYYY);
    }

    private static LocalDate hoySiNull(LocalDate f) {
        return f != null ? f : LocalDate.now(ECUADOR);
    }

    private static <T> BigDecimal suma(List<T> l, java.util.function.Function<T, BigDecimal> f) {
        return l.stream().map(f).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    static BigDecimal r2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // Cantidad y precio unitario admiten hasta 6 decimales en las versiones 1.1.0.
    private static BigDecimal seis(BigDecimal v) {
        return v.scale() > 6 ? v.setScale(6, RoundingMode.HALF_UP) : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
