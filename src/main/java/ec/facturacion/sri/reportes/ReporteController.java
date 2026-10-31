package ec.facturacion.sri.reportes;

import ec.facturacion.sri.seguridad.Sesion;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reportes")
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class ReporteController {
    private static final Map<String, String> TIPOS = Map.of("01", "FACTURA", "03", "LIQUIDACION DE COMPRA",
            "04", "NOTA DE CREDITO", "05", "NOTA DE DEBITO", "06", "GUIA DE REMISION", "07", "RETENCION");

    private final JdbcClient db;
    private final DataSource ds;

    public ReporteController(JdbcClient db, DataSource ds) {
        this.db = db;
        this.ds = ds;
    }

    private static void validarRango(LocalDate desde, LocalDate hasta) {
        if (hasta.isBefore(desde)) throw new IllegalArgumentException("hasta no puede ser anterior a desde");
        if (ChronoUnit.DAYS.between(desde, hasta) > 366) throw new IllegalArgumentException("El rango máximo es de un año");
    }

    /**
     * Resumen del periodo: cantidades y montos por tipo y estado, y las ventas netas
     * (facturas + notas de débito − notas de crédito, solo AUTORIZADAS) útiles para la declaración de IVA.
     */
    @GetMapping("/resumen")
    public Map<String, Object> resumen(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                       @RequestParam(required = false) String ruc) {
        validarRango(desde, hasta);
        record Grupo(String codDoc, String estado, long cantidad, BigDecimal subtotal, BigDecimal iva, BigDecimal total) {}
        List<Grupo> grupos = db.sql("""
                        select c.cod_doc, c.estado, count(*) as cantidad, coalesce(sum(c.subtotal), 0) as subtotal,
                               coalesce(sum(c.iva), 0) as iva, coalesce(sum(c.importe_total), 0) as total
                        from comprobante c join emisor e on e.id = c.emisor_id
                        where c.tenant_id = ? and c.fecha_emision between ? and ? and (?::text is null or e.ruc = ?)
                        group by c.cod_doc, c.estado order by c.cod_doc, c.estado""")
                .params(Sesion.de(jwt).tenant(), desde, hasta, ruc, ruc).query(Grupo.class).list();

        BigDecimal sub = BigDecimal.ZERO, iva = BigDecimal.ZERO, total = BigDecimal.ZERO, retenido = BigDecimal.ZERO;
        List<Map<String, Object>> detalle = new ArrayList<>();
        for (Grupo g : grupos) {
            var m = new LinkedHashMap<String, Object>();
            m.put("codDoc", g.codDoc());
            m.put("tipo", TIPOS.get(g.codDoc()));
            m.put("estado", g.estado());
            m.put("cantidad", g.cantidad());
            m.put("subtotal", g.subtotal());
            m.put("iva", g.iva());
            m.put("total", g.total());
            detalle.add(m);
            if (!g.estado().equals("AUTORIZADO")) continue;
            int signo = switch (g.codDoc()) { case "01", "05" -> 1; case "04" -> -1; default -> 0; };
            sub = sub.add(g.subtotal().multiply(BigDecimal.valueOf(signo)));
            iva = iva.add(g.iva().multiply(BigDecimal.valueOf(signo)));
            total = total.add(g.total().multiply(BigDecimal.valueOf(signo)));
            if (g.codDoc().equals("07")) retenido = retenido.add(g.total());
        }
        var r = new LinkedHashMap<String, Object>();
        r.put("desde", desde.toString());
        r.put("hasta", hasta.toString());
        r.put("ventasNetas", Map.of("subtotal", sub, "iva", iva, "total", total));
        r.put("retencionesEmitidas", retenido);
        r.put("detalle", detalle);
        return r;
    }

    /** Listado en CSV (UTF-8 con BOM para que Excel muestre bien las tildes). Se envía fila por fila. */
    @GetMapping(value = "/comprobantes.csv", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> csv(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                     @RequestParam(required = false) String codDoc,
                                                     @RequestParam(required = false) String estado,
                                                     @RequestParam(required = false) String ruc) {
        validarRango(desde, hasta);
        long tenant = Sesion.de(jwt).tenant();
        StreamingResponseBody cuerpo = out -> {
            var w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            w.write('﻿');
            w.write("fecha_emision,tipo,numero,clave_acceso,estado,numero_autorizacion,fecha_autorizacion,"
                    + "ruc_emisor,receptor_identificacion,receptor_razon_social,subtotal,iva,total\r\n");
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false); // necesario para que Postgres entregue las filas por lotes
                try (var ps = c.prepareStatement("""
                        select c.fecha_emision, c.cod_doc, p.estab || '-' || p.pto_emi || '-' || c.secuencial as numero,
                               c.clave_acceso, c.estado, c.numero_autorizacion, c.fecha_autorizacion, e.ruc,
                               c.receptor_identificacion, c.receptor_razon_social, c.subtotal, c.iva, c.importe_total
                        from comprobante c join emisor e on e.id = c.emisor_id join punto_emision p on p.id = c.punto_emision_id
                        where c.tenant_id = ? and c.fecha_emision between ? and ?
                          and (?::text is null or c.cod_doc = ?) and (?::text is null or c.estado = ?) and (?::text is null or e.ruc = ?)
                        order by c.fecha_emision, c.cod_doc, numero""")) {
                    ps.setFetchSize(500);
                    Object[] params = {tenant, desde, hasta, codDoc, codDoc, estado, estado, ruc, ruc};
                    for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            w.write(String.join(",", rs.getString(1), TIPOS.getOrDefault(rs.getString(2), rs.getString(2)),
                                    rs.getString(3), rs.getString(4), rs.getString(5), celda(rs.getString(6)),
                                    rs.getTimestamp(7) == null ? "" : rs.getTimestamp(7).toInstant().toString(),
                                    rs.getString(8), celda(rs.getString(9)), celda(rs.getString(10)),
                                    num(rs.getBigDecimal(11)), num(rs.getBigDecimal(12)), num(rs.getBigDecimal(13))));
                            w.write("\r\n");
                        }
                    }
                }
                c.commit();
            } catch (java.sql.SQLException e) {
                throw new java.io.IOException(e);
            }
            w.flush();
        };
        String nombre = "comprobantes_" + desde + "_" + hasta + ".csv";
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"" + nombre + "\"").body(cuerpo);
    }

    /**
     * Texto seguro para CSV: comillas cuando hace falta y apóstrofo delante de =, +, -, @ para que
     * Excel no lo interprete como fórmula (un nombre de cliente malicioso podría ejecutar algo al abrirlo).
     */
    static String celda(String v) {
        if (v == null) return "";
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static String num(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
