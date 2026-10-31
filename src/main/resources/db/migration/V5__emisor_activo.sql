-- Un emisor desactivado no puede emitir (por ejemplo, un RUC que dejó de facturar).
alter table emisor add column activo boolean not null default true;
