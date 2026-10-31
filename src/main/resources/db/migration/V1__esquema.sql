create table tenant (
    id         bigint generated always as identity primary key,
    nombre     text        not null,
    activo     boolean     not null default true,
    creado_en  timestamptz not null default now()
);

create table usuario (
    id            bigint generated always as identity primary key,
    tenant_id     bigint references tenant (id),
    email         text        not null unique,
    password_hash text        not null,
    rol           text        not null check (rol in ('SUPERADMIN', 'ADMIN', 'USER')),
    activo        boolean     not null default true,
    creado_en     timestamptz not null default now(),
    -- Solo el SUPERADMIN vive fuera de un tenant.
    check ((rol = 'SUPERADMIN') = (tenant_id is null))
);

create table refresh_token (
    id         bigint generated always as identity primary key,
    usuario_id bigint      not null references usuario (id),
    familia    uuid        not null,
    hash       text        not null unique,
    expira_en  timestamptz not null,
    usado      boolean     not null default false,
    revocado   boolean     not null default false
);
create index on refresh_token (familia);
create index on refresh_token (usuario_id);

create table emisor (
    id                     bigint generated always as identity primary key,
    tenant_id              bigint      not null references tenant (id),
    ruc                    char(13)    not null,
    razon_social           text        not null,
    nombre_comercial       text,
    dir_matriz             text        not null,
    obligado_contabilidad  boolean     not null default false,
    contribuyente_especial text,
    agente_retencion       text,
    contribuyente_rimpe    text,
    -- Certificado .p12 y su clave cifrados con AES-256-GCM.
    cert_p12               bytea,
    cert_clave             bytea,
    cert_huella            text,
    cert_sujeto            text,
    cert_expira            timestamptz,
    creado_en              timestamptz not null default now(),
    unique (tenant_id, ruc)
);

create table punto_emision (
    id                  bigint generated always as identity primary key,
    emisor_id           bigint  not null references emisor (id),
    estab               char(3) not null,
    pto_emi             char(3) not null,
    dir_establecimiento text    not null,
    activo              boolean not null default true,
    unique (emisor_id, estab, pto_emi)
);

create table secuencial (
    punto_emision_id bigint  not null references punto_emision (id),
    cod_doc          char(2) not null,
    ultimo           bigint  not null,
    primary key (punto_emision_id, cod_doc)
);

create table comprobante (
    id                     bigint generated always as identity primary key,
    tenant_id              bigint      not null references tenant (id),
    emisor_id              bigint      not null references emisor (id),
    punto_emision_id       bigint      not null references punto_emision (id),
    cod_doc                char(2)     not null,
    secuencial             char(9)     not null,
    clave_acceso           char(49)    not null unique,
    idempotency_key        text,
    estado                 text        not null check (estado in
        ('PENDIENTE', 'EN_PROCESO', 'AUTORIZADO', 'NO_AUTORIZADO', 'DEVUELTA', 'ERROR', 'ANULADO')),
    fecha_emision          date        not null,
    solicitud              jsonb       not null,
    xml_firmado            text        not null,
    xml_autorizado         text,
    numero_autorizacion    text,
    fecha_autorizacion     timestamptz,
    mensajes               jsonb       not null default '[]',
    intentos               int         not null default 0,
    proximo_intento        timestamptz not null default now(),
    creado_en              timestamptz not null default now(),
    actualizado_en         timestamptz not null default now(),
    unique (tenant_id, idempotency_key),
    unique (punto_emision_id, cod_doc, secuencial)
);
create index on comprobante (proximo_intento) where estado in ('PENDIENTE', 'EN_PROCESO');
create index on comprobante (tenant_id, creado_en desc);

create table auditoria (
    id         bigint generated always as identity primary key,
    usuario_id bigint,
    tenant_id  bigint,
    accion     text        not null,
    detalle    jsonb,
    ip         text,
    creado_en  timestamptz not null default now()
);

-- La auditoría es inmutable: nadie puede editar ni borrar registros.
create function auditoria_inmutable() returns trigger language plpgsql as $$
begin
    raise exception 'La auditoría no se puede modificar';
end $$;
create trigger auditoria_inmutable before update or delete on auditoria
    for each row execute function auditoria_inmutable();
