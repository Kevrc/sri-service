-- Cola de emails con el RIDE y el XML para el receptor del comprobante.
create table correo (
    id             bigint generated always as identity primary key,
    tenant_id      bigint      not null references tenant (id),
    comprobante_id bigint      not null references comprobante (id),
    destinatarios  text        not null,
    estado         text        not null default 'PENDIENTE' check (estado in ('PENDIENTE', 'ENVIADO', 'FALLIDO')),
    intentos       int         not null default 0,
    proximo_intento timestamptz not null default now(),
    error          text,
    creado_en      timestamptz not null default now(),
    enviado_en     timestamptz
);
create index on correo (proximo_intento) where estado = 'PENDIENTE';
create index on correo (comprobante_id);
