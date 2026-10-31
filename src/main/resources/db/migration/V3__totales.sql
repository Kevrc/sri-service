-- Totales y receptor en columnas, para reportes rápidos sin leer el XML.
alter table comprobante
    add column subtotal              numeric(14, 2),
    add column iva                   numeric(14, 2),
    add column importe_total         numeric(14, 2),
    add column receptor_identificacion text,
    add column receptor_razon_social   text;

-- Completa los comprobantes ya emitidos leyendo su XML.
with x as (select id, cod_doc, xml_firmado::xml as d from comprobante)
update comprobante c set
    subtotal = case when x.cod_doc in ('01', '03', '04', '05')
                    then ((xpath('/*/*/totalSinImpuestos/text()', x.d))[1])::text::numeric end,
    iva = case when x.cod_doc in ('01', '03', '04')
               then (select coalesce(sum(v::text::numeric), 0) from unnest(xpath('/*/*/totalConImpuestos/totalImpuesto/valor/text()', x.d)) v)
               when x.cod_doc = '05'
               then (select coalesce(sum(v::text::numeric), 0) from unnest(xpath('/*/infoNotaDebito/impuestos/impuesto/valor/text()', x.d)) v) end,
    importe_total = case x.cod_doc
        when '01' then ((xpath('/*/infoFactura/importeTotal/text()', x.d))[1])::text::numeric
        when '03' then ((xpath('/*/infoLiquidacionCompra/importeTotal/text()', x.d))[1])::text::numeric
        when '04' then ((xpath('/*/infoNotaCredito/valorModificacion/text()', x.d))[1])::text::numeric
        when '05' then ((xpath('/*/infoNotaDebito/valorTotal/text()', x.d))[1])::text::numeric
        when '07' then (select coalesce(sum(v::text::numeric), 0) from unnest(xpath('//retencion/valorRetenido/text()', x.d)) v) end,
    receptor_identificacion = coalesce(
        ((xpath('/*/*/identificacionComprador/text()', x.d))[1])::text,
        ((xpath('/*/*/identificacionProveedor/text()', x.d))[1])::text,
        ((xpath('/*/*/identificacionSujetoRetenido/text()', x.d))[1])::text,
        ((xpath('//destinatario/identificacionDestinatario/text()', x.d))[1])::text),
    receptor_razon_social = coalesce(
        ((xpath('/*/*/razonSocialComprador/text()', x.d))[1])::text,
        ((xpath('/*/*/razonSocialProveedor/text()', x.d))[1])::text,
        ((xpath('/*/*/razonSocialSujetoRetenido/text()', x.d))[1])::text,
        ((xpath('//destinatario/razonSocialDestinatario/text()', x.d))[1])::text)
from x where x.id = c.id;

create index on comprobante (tenant_id, fecha_emision);
