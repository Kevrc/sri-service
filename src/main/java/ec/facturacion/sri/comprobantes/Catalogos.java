package ec.facturacion.sri.comprobantes;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Tablas de la ficha técnica del SRI. Las usa el cálculo de totales y se publican en /catalogos. */
@RestController
@RequestMapping("/catalogos")
public class Catalogos {

    public record Item(String codigo, String descripcion, BigDecimal porcentaje) {
        Item(String codigo, String descripcion) { this(codigo, descripcion, null); }
    }

    /** IVA: codigoPorcentaje -> tarifa (tabla 17). */
    public static final List<Item> IVA = List.of(
            new Item("0", "0%", new BigDecimal("0")),
            new Item("2", "12%", new BigDecimal("12")),
            new Item("3", "14%", new BigDecimal("14")),
            new Item("4", "15%", new BigDecimal("15")),
            new Item("5", "5%", new BigDecimal("5")),
            new Item("6", "No objeto de impuesto", new BigDecimal("0")),
            new Item("7", "Exento de IVA", new BigDecimal("0")),
            new Item("8", "IVA diferenciado", new BigDecimal("8")),
            new Item("10", "13%", new BigDecimal("13")));

    public static BigDecimal tarifaIva(String codigoPorcentaje) {
        return IVA.stream().filter(i -> i.codigo().equals(codigoPorcentaje)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Código de IVA inválido: " + codigoPorcentaje + " (ver /catalogos/impuestos)"))
                .porcentaje();
    }

    // Porcentajes referenciales: el SRI los cambia por resolución; el emisor envía el porcentaje vigente.
    static final List<Item> RETENCION_IVA = List.of(
            new Item("9", "Retención IVA 10%", new BigDecimal("10")),
            new Item("10", "Retención IVA 20%", new BigDecimal("20")),
            new Item("1", "Retención IVA 30%", new BigDecimal("30")),
            new Item("11", "Retención IVA 50%", new BigDecimal("50")),
            new Item("2", "Retención IVA 70%", new BigDecimal("70")),
            new Item("3", "Retención IVA 100%", new BigDecimal("100")),
            new Item("7", "Retención en cero", new BigDecimal("0")),
            new Item("8", "No procede retención", new BigDecimal("0")));

    static final List<Item> RETENCION_RENTA = List.of(
            new Item("303", "Honorarios profesionales y demás pagos por servicios relacionados con el título profesional"),
            new Item("304", "Servicios predomina el intelecto no relacionados con el título profesional"),
            new Item("307", "Servicios predomina la mano de obra"),
            new Item("308", "Utilización o aprovechamiento de la imagen o renombre"),
            new Item("309", "Servicios prestados por medios de comunicación y agencias de publicidad"),
            new Item("310", "Servicio de transporte privado de pasajeros o transporte público o privado de carga"),
            new Item("312", "Transferencia de bienes muebles de naturaleza corporal"),
            new Item("319", "Arrendamiento mercantil"),
            new Item("320", "Arrendamiento de bienes inmuebles"),
            new Item("322", "Seguros y reaseguros (primas y cesiones)"),
            new Item("332", "Otras compras de bienes y servicios no sujetas a retención"),
            new Item("343", "Otras retenciones aplicables el 1%"),
            new Item("3440", "Otras retenciones aplicables a otros porcentajes"));

    static final Map<String, List<Item>> TODOS = new LinkedHashMap<>();

    static {
        TODOS.put("tipos-comprobante", List.of(new Item("01", "Factura"), new Item("03", "Liquidación de compra"),
                new Item("04", "Nota de crédito"), new Item("05", "Nota de débito"), new Item("06", "Guía de remisión"),
                new Item("07", "Comprobante de retención")));
        TODOS.put("impuestos", IVA);
        TODOS.put("retenciones-iva", RETENCION_IVA);
        TODOS.put("retenciones-renta", RETENCION_RENTA);
        TODOS.put("formas-pago", List.of(new Item("01", "Sin utilización del sistema financiero"),
                new Item("15", "Compensación de deudas"), new Item("16", "Tarjeta de débito"),
                new Item("17", "Dinero electrónico"), new Item("18", "Tarjeta prepago"), new Item("19", "Tarjeta de crédito"),
                new Item("20", "Otros con utilización del sistema financiero"), new Item("21", "Endoso de títulos")));
        TODOS.put("tipos-identificacion", List.of(new Item("04", "RUC"), new Item("05", "Cédula"),
                new Item("06", "Pasaporte"), new Item("07", "Consumidor final"), new Item("08", "Identificación del exterior")));
        TODOS.put("documentos-sustento", List.of(
                new Item("00", "Casos especiales cuyo sustento no aplica en las opciones anteriores"),
                new Item("01", "Crédito tributario para declaración de IVA (servicios y bienes distintos de inventarios y activos fijos)"),
                new Item("02", "Costo o gasto para declaración de IR (servicios y bienes distintos de inventarios y activos fijos)"),
                new Item("03", "Activo fijo - crédito tributario para declaración de IVA"),
                new Item("04", "Activo fijo - costo o gasto para declaración de IR"),
                new Item("05", "Liquidación de gastos de viaje, hospedaje y alimentación (a nombre de empleados)"),
                new Item("06", "Inventario - crédito tributario para declaración de IVA"),
                new Item("07", "Inventario - costo o gasto para declaración de IR"),
                new Item("08", "Valor pagado para solicitar reembolso de gasto (intermediario)"),
                new Item("09", "Reembolso por siniestros"),
                new Item("10", "Distribución de dividendos, beneficios o utilidades")));
        TODOS.put("motivos-traslado", List.of(new Item("VENTA", "Venta"), new Item("COMPRA", "Compra"),
                new Item("TRASLADO", "Traslado entre establecimientos de una misma empresa"),
                new Item("DEVOLUCION", "Devolución"), new Item("CONSIGNACION", "Consignación"),
                new Item("IMPORTACION", "Importación"), new Item("EXPORTACION", "Exportación"), new Item("OTROS", "Otros")));
    }

    @GetMapping
    public Map<String, List<Item>> todos() {
        return TODOS;
    }

    @GetMapping("/{nombre}")
    public List<Item> uno(@PathVariable String nombre) {
        var l = TODOS.get(nombre);
        if (l == null) throw new NoSuchElementException("Catálogo no encontrado. Disponibles: " + TODOS.keySet());
        return l;
    }
}
