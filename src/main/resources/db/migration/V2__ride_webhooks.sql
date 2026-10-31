alter table emisor add column logo bytea, add column logo_tipo text;

create table webhook (
    id        bigint generated always as identity primary key,
    tenant_id bigint      not null references tenant (id),
    url       text        not null,
    -- Secreto HMAC cifrado con AES-256-GCM (se necesita en claro para firmar).
    secreto   bytea       not null,
    eventos   text[]      not null,
    activo    boolean     not null default true,
    creado_en timestamptz not null default now()
);
create index on webhook (tenant_id);

create table webhook_entrega (
    id               bigint generated always as identity primary key,
    webhook_id       bigint      not null references webhook (id) on delete cascade,
    tenant_id        bigint      not null references tenant (id),
    evento           text        not null,
    clave_acceso     char(49)    not null,
    payload          jsonb       not null,
    estado           text        not null default 'PENDIENTE' check (estado in ('PENDIENTE', 'ENTREGADO', 'FALLIDO')),
    intentos         int         not null default 0,
    proximo_intento  timestamptz not null default now(),
    respuesta_status int,
    respuesta        text,
    creado_en        timestamptz not null default now(),
    entregado_en     timestamptz
);
create index on webhook_entrega (proximo_intento) where estado = 'PENDIENTE';
create index on webhook_entrega (tenant_id, creado_en desc);
