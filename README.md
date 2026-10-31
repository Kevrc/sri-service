# Servicio SRI — Facturación electrónica Ecuador

API REST (Java 21 + Spring Boot + PostgreSQL) que cualquier sistema (Go, PHP, .NET, JS…) usa para emitir comprobantes electrónicos al SRI.
Multiempresa, con JWT y roles. Genera la clave de acceso y el secuencial, arma y firma el XML (XAdES-BES), lo envía al SRI y reintenta solo si el SRI falla.

Comprobantes: `factura`, `notaCredito`, `notaDebito`, `comprobanteRetencion`, `guiaRemision`, `liquidacionCompra`.

## Ejecutar

### En un servidor (Docker)

```bash
cp .env.example .env      # completar JWT_SECRET, ENCRYPTION_KEY, DB_PASSWORD y SUPERADMIN_*
docker compose up -d --build
```

La API queda en `http://127.0.0.1:8080` (solo localhost): publícala detrás de un proxy con HTTPS (Nginx, Caddy, Traefik).
La imagen corre sin root, con health check en `/status`. **Respalda el volumen `pgdata` y la `ENCRYPTION_KEY`.**

### En desarrollo

```bash
docker compose up -d db                   # solo PostgreSQL (DB_PORT=55432 si el 5432 está ocupado)
mvn package
JWT_SECRET=$(openssl rand -base64 48) ENCRYPTION_KEY=$(openssl rand -base64 32) \
SUPERADMIN_EMAIL=admin@tuempresa.ec SUPERADMIN_PASSWORD='una-clave-larga' \
java -jar target/sri-service.jar
```

Documentación interactiva (Swagger): `http://localhost:8080/api`. Haz login en `POST /auth/login`, pulsa **Authorize** y pega el `accessToken`.

| Variable | Default | Descripción |
|---|---|---|
| `JWT_SECRET` | — obligatoria | Firma de los tokens (≥ 32 caracteres) |
| `ENCRYPTION_KEY` | — obligatoria | AES-256 para certificados y secretos. **Si se pierde, hay que volver a subir los .p12 y recrear los webhooks** |
| `SUPERADMIN_EMAIL` / `SUPERADMIN_PASSWORD` | — | Crea el superadmin la primera vez (no hay credenciales por defecto) |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/sri`, `sri`, `sri` | Base de datos |
| `SRI_AMBIENTE` | `1` | `1` = pruebas (celcer), `2` = producción (cel) |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM` | — / `587` | Email del RIDE y XML a los clientes. Sin `SMTP_HOST` queda desactivado. Usa STARTTLS (`SMTP_STARTTLS=false` solo en pruebas) |
| `ALERTAS_EMAIL` | — | A quién avisar si el SRI cae o hay comprobantes atascados (varios separados por coma) |
| `METRICS_TOKEN` | — | Token para leer `/prometheus`; sin él las métricas quedan cerradas |
| `WEBHOOKS_PERMITIR_HTTP`, `WEBHOOKS_PERMITIR_RED_PRIVADA` | `false` | **Solo pruebas locales**: permiten webhooks por http y hacia localhost/red interna |
| `SWAGGER_ENABLED` | `true` | Swagger UI en `/api`; en producción conviene `false` |
| `PORT` | `8080` | Puerto HTTP |

## Flujo de uso

1. **SUPERADMIN**: `POST /tenants` → `POST /auth/register` (ADMIN del tenant).
2. **ADMIN**: `POST /emisores` (RUC) → `PUT /emisores/{id}/certificado` (multipart `archivo` + `clave`) → `POST /emisores/{id}/puntos-emision`.
3. **ADMIN / USER**: `POST /sri/factura/emitir` (y los demás tipos) con JSON simple; el servicio calcula subtotales, IVA y totales.

## Endpoints

| Método | Ruta | Rol | Descripción |
|---|---|---|---|
| POST | `/auth/login` | público | `accessToken` (15 min) + `refreshToken` (30 días) |
| POST | `/auth/refresh` | público | Rota el refresh token (cada uno sirve una vez; reusarlo revoca la sesión) |
| POST | `/auth/logout` | público | Revoca el refresh token (cuerpo o cookie) y borra la cookie |
| POST | `/auth/register` | SUPERADMIN, ADMIN | Crear usuario (ADMIN solo en su tenant) |
| PATCH | `/auth/change-password` | todos | Cambia la clave y cierra todas las sesiones (400 si la clave actual es incorrecta) |
| GET | `/usuarios` | SUPERADMIN, ADMIN | Listar (ADMIN: su tenant; SUPERADMIN: todos o `?tenantId=`) |
| PATCH | `/usuarios/{id}` | SUPERADMIN, ADMIN | `{"activo", "rol"}`: desactivar cierra sus sesiones |
| POST | `/usuarios/{id}/clave` | SUPERADMIN, ADMIN | Clave temporal `{"nueva"}`; cierra sus sesiones |
| POST/GET | `/tenants` | SUPERADMIN | Empresas clientes |
| PATCH | `/tenants/{id}` | SUPERADMIN | `{"nombre", "activo"}`: desactivado no puede iniciar sesión |
| POST/GET | `/emisores`, `/emisores/{id}` | ADMIN crea, todos leen | RUCs del tenant |
| PATCH | `/emisores/{id}` | ADMIN | Cambios parciales (el RUC no cambia); `""` borra un campo opcional; `activo: false` deja de emitir |
| PUT/GET | `/emisores/{id}/certificado` | ADMIN sube, todos leen | .p12 (se guarda cifrado) y su vigencia |
| PUT | `/emisores/{id}/logo` | ADMIN | Logo del RIDE (multipart `archivo`, PNG/JPEG ≤ 300 KB) |
| POST/GET | `/emisores/{id}/puntos-emision` | ADMIN crea, todos leen | Establecimiento + punto de emisión (con `ultimosSecuenciales` por tipo) |
| PATCH | `/emisores/{id}/puntos-emision/{puntoId}` | ADMIN | `{"dirEstablecimiento", "activo"}` |
| POST | `/sri/factura/emitir` | ADMIN, USER | Factura (JSON simple, calcula totales) |
| POST | `/sri/nota-credito/emitir` | ADMIN, USER | Nota de crédito |
| POST | `/sri/nota-debito/emitir` | ADMIN, USER | Nota de débito |
| POST | `/sri/retencion/emitir` | ADMIN, USER | Comprobante de retención (v2.0.0, pagos locales) |
| POST | `/sri/guia-remision/emitir` | ADMIN, USER | Guía de remisión |
| POST | `/sri/liquidacion-compra/emitir` | ADMIN, USER | Liquidación de compra |
| POST | `/sri/comprobantes` | ADMIN, USER | Avanzado: contenido con la estructura del XSD ([ejemplo](ejemplos/factura.json)) |
| GET | `/sri/comprobantes` | ADMIN, USER | Listar: `estado`, `codDoc`, `ruc`, `desde`, `hasta` (aaaa-mm-dd), `pagina`, `tamano` |
| GET | `/sri/comprobantes/{clave}` | ADMIN, USER | Detalle |
| GET | `/sri/comprobantes/{clave}/xml` | ADMIN, USER | XML autorizado (o firmado si aún no se autoriza) |
| GET | `/sri/comprobantes/{clave}/ride` | ADMIN, USER | RIDE en PDF con código de barras y QR (autorizados; los anulados con marca de agua) |
| POST | `/sri/comprobantes/{clave}/email` | ADMIN, USER | Envía o reenvía RIDE + XML (`{"destinatarios": [...]}` opcional) |
| GET | `/sri/comprobantes/{clave}/emails` | ADMIN, USER | Historial de envíos por email |
| GET | `/sri/verificar/{clave}` | ADMIN, USER | Consulta al SRI ya mismo |
| POST | `/sri/comprobantes/{clave}/reintentar` | ADMIN, USER | Reintenta uno en `ERROR` |
| PATCH | `/sri/comprobantes/{clave}/anular` | ADMIN | Marca ANULADO un AUTORIZADO (`{"motivo": ...}`). **También hay que anularlo en SRI en línea** |
| POST | `/sri/sincronizar` | ADMIN, USER | Procesa ya todos los pendientes del tenant |
| GET | `/catalogos`, `/catalogos/{nombre}` | todos | `impuestos`, `retenciones-iva`, `retenciones-renta`, `formas-pago`, `tipos-identificacion`, `documentos-sustento`, `motivos-traslado`, `tipos-comprobante` |
| POST/GET | `/webhooks` | ADMIN | Registrar URL (`{"url", "eventos"?}`); el `secreto` se muestra solo al crear |
| PATCH | `/webhooks/{id}` | ADMIN | `{"activo", "url", "eventos"}`: pausado acumula y entrega al reanudar |
| POST | `/webhooks/{id}/rotar-secreto` | ADMIN | Nuevo secreto (se muestra una sola vez; el anterior deja de valer) |
| DELETE | `/webhooks/{id}` | ADMIN | Eliminar |
| GET | `/webhooks/logs` | ADMIN | Historial de entregas: `estado`, `claveAcceso`, `pagina`, `tamano` |
| POST | `/webhooks/logs/{id}/reenviar` | ADMIN | Reintentar una entrega |
| GET | `/reportes/resumen` | ADMIN, USER | `desde`, `hasta` (aaaa-mm-dd, máx. 1 año), `ruc`: cantidades y montos por tipo/estado y ventas netas |
| GET | `/reportes/comprobantes.csv` | ADMIN, USER | Mismo rango + `codDoc`, `estado`, `ruc`: listado para Excel |
| GET | `/reportes/ats` | ADMIN | ATS del mes en XML para DIMM: `ruc`, `anio`, `mes` |
| GET | `/reportes/ats/resumen` | ADMIN | Compras incluidas, total de ventas y **advertencias** a revisar antes de presentar |
| GET | `/prometheus` | `Bearer METRICS_TOKEN` | Métricas para Prometheus/Grafana |
| GET | `/status` | público | Health check (DB y disco) |

Todos los `POST .../emitir` y `/sri/comprobantes` aceptan el header `Idempotency-Key`. Las respuestas incluyen `subtotal`, `iva`, `total`, `receptorIdentificacion`, `receptorRazonSocial` e `idempotencyKey`.

**Errores**: formato Problem Details (RFC 9457). Si falla una validación, `errores` dice el campo:

```json
{"status": 400, "detail": "Datos inválidos: ruc: RUC inválido (13 dígitos terminados en 001)",
 "errores": [{"campo": "ruc", "mensaje": "RUC inválido (13 dígitos terminados en 001)"}]}
```

**Navegadores**: con la cabecera `X-Token-Cookie: true` en `/auth/login`, el refresh token no viene en el cuerpo sino en una cookie `HttpOnly; Secure; SameSite=Strict; Path=/auth`. Luego `/auth/refresh` y `/auth/logout` se llaman sin cuerpo y el navegador envía la cookie. El JavaScript nunca ve el refresh token. Los sistemas servidor a servidor siguen usando el cuerpo.

### Ejemplo: `POST /sri/factura/emitir`

```json
{
  "ruc": "0999999999001", "establecimiento": "001", "puntoEmision": "001",
  "fechaEmision": "2026-10-31",
  "comprador": { "tipoIdentificacion": "05", "identificacion": "0926789017", "razonSocial": "JUAN PEREZ", "email": "juan@example.com" },
  "items": [
    { "codigo": "P001", "descripcion": "Servicio", "cantidad": 1, "precioUnitario": 1000, "descuento": 0, "iva": "4" }
  ],
  "pagos": [ { "formaPago": "20" } ],
  "infoAdicional": { "Pedido": "PED-77" }
}
```

- `iva` es el `codigoPorcentaje` del SRI (`4` = 15%, `0` = 0%, `6` = no objeto, `7` = exento; ver `/catalogos/impuestos`).
- Fechas en formato ISO `aaaa-mm-dd`; si falta `fechaEmision` se usa la fecha de hoy en Ecuador.
- Un solo pago sin `total` toma el importe total; con varios, deben sumar exactamente el total.
- El `email` del cliente va a `infoAdicional` como "Email".
- `dirEstablecimiento`, `contribuyenteEspecial` y `obligadoContabilidad` salen del emisor y del punto de emisión registrados.
- Retención: los porcentajes los envía el emisor (`porcentaje`) porque el SRI los cambia por resolución; `/catalogos/retenciones-renta` es referencial.

### Cuerpo de `POST /sri/comprobantes` (avanzado)

```json
{ "ruc": "0999999999001", "establecimiento": "001", "puntoEmision": "001",
  "tipo": "factura", "version": "1.1.0", "contenido": { "infoFactura": {...}, "detalles": {...} } }
```

- `contenido` sigue **la estructura y el orden del XSD del SRI**, sin `infoTributaria` (la arma el servicio con el emisor, el secuencial y la clave de acceso).
- `"@nombre"` = atributo, `"#text"` = texto, arreglos = elemento repetido. Montos como números (`10.00`).
- Versiones: factura `1.1.0`, nota de crédito `1.1.0`, nota de débito `1.0.0`, retención `2.0.0`, guía `1.1.0`, liquidación `1.1.0`.

### Estados

| Estado | Significado |
|---|---|
| `PENDIENTE` | Firmado y guardado; aún no llega al SRI (se reintenta solo) |
| `EN_PROCESO` | El SRI lo recibió; esperando autorización (se consulta solo) |
| `AUTORIZADO` | Listo; incluye `numeroAutorizacion` y el XML autorizado |
| `NO_AUTORIZADO` / `DEVUELTA` | El SRI lo rechazó: revisar `mensajes`, corregir y emitir de nuevo |
| `ERROR` | Agotó 15 reintentos (horas sin SRI): usar `/reintentar` |

**Idempotencia:** envía `Idempotency-Key` (ej. el id de tu pedido). Si tu sistema reenvía por un timeout, recibes el mismo comprobante en vez de uno duplicado.

## Webhooks

Eventos: `comprobante.autorizado`, `comprobante.no_autorizado`, `comprobante.devuelta`, `comprobante.error`, `comprobante.anulado` (por defecto, todos).

```http
POST https://tu-sistema.com/webhook-sri
X-SRI-Evento: comprobante.autorizado
X-SRI-Entrega: 42                       ← único por entrega: úsalo para ignorar duplicados
X-SRI-Timestamp: 1793480400
X-SRI-Firma: sha256=<hex de HMAC-SHA256(secreto, timestamp + "." + cuerpo)>

{"evento":"comprobante.autorizado","claveAcceso":"...","estado":"AUTORIZADO","numeroAutorizacion":"...",
 "idempotencyKey":"venta-42","subtotal":100.00,"iva":15.00,"total":115.00, ...}
```

- Responde 2xx para confirmar. Si no, se reintenta con esperas crecientes (hasta 10 veces, ~8 horas) y luego queda `FALLIDO` (reenviable).
- El evento se guarda en la misma transacción que el cambio de estado: nunca se pierde (tampoco si el webhook está pausado).
- `idempotencyKey` es la que enviaste al emitir: sirve para asociar el aviso a tu venta aunque llegue antes que la respuesta de la emisión.
- Solo URLs `https` a direcciones públicas (bloquea localhost, redes internas y metadata de la nube) y sin seguir redirecciones.

Verificar la firma en Go:

```go
mac := hmac.New(sha256.New, []byte(secreto))
mac.Write([]byte(r.Header.Get("X-SRI-Timestamp") + "." + string(cuerpo)))
valida := hmac.Equal([]byte("sha256="+hex.EncodeToString(mac.Sum(nil))), []byte(r.Header.Get("X-SRI-Firma")))
// Rechaza también timestamps de más de 5 minutos para evitar reenvíos maliciosos.
```

## RIDE

PDF generado al vuelo desde el XML firmado: datos del emisor y logo, número y fecha de autorización, ambiente, clave de acceso con código de barras (Code 128) y QR, receptor, detalle según el tipo, totales, formas de pago e información adicional.
La plantilla es HTML editable: [`src/main/resources/templates/ride.html`](src/main/resources/templates/ride.html).

## Reportes

- **Resumen** (`/reportes/resumen`): por tipo y estado, cantidad, subtotal, IVA y total. `ventasNetas` = facturas + notas de débito − notas de crédito (solo autorizadas), útil para la declaración de IVA; `retencionesEmitidas` = total retenido.
- **CSV** (`/reportes/comprobantes.csv`): fecha, tipo, número, clave, estado, autorización, receptor y montos. UTF-8 con BOM (Excel muestra bien las tildes), se transmite fila por fila y neutraliza fórmulas (`=`, `+`, `-`, `@`) para que un nombre malicioso no se ejecute al abrirlo en Excel.

## Email al cliente

Cuando un comprobante queda **AUTORIZADO** y su `infoAdicional` tiene un campo "Email" o "Correo" (los endpoints simplificados lo ponen desde `comprador.email`), se envía al cliente el RIDE (PDF) y el XML autorizado, a nombre de la razón social del emisor. Si el SMTP falla, se reintenta solo (hasta 8 veces, ~8 horas). Se guarda en la misma transacción que la autorización, igual que los webhooks.

## Alertas y métricas

- **Alertas por email** a `ALERTAS_EMAIL`: cuando fallan 5 llamadas seguidas al SRI ("SRI no responde"), cuando vuelve ("SRI recuperado") y, como máximo una vez por hora, si hay comprobantes pendientes por más de 30 minutos o que pasaron a ERROR.
- **Prometheus** en `/prometheus` (cabecera `Authorization: Bearer <METRICS_TOKEN>`):

| Métrica | Qué mide |
|---|---|
| `sri_disponible` | 1 si el SRI responde, 0 si está caído |
| `sri_llamadas_seconds` | Tiempo de cada llamada al SRI por `operacion` (recepcion/autorizacion) y `resultado` (ok/error) |
| `sri_comprobantes_emitidos_total`, `sri_comprobantes_finalizados_total` | Comprobantes emitidos (por `codDoc`) y finalizados (por `estado`) |
| `sri_comprobantes_pendientes`, `sri_comprobantes_pendiente_antiguedad_segundos`, `sri_comprobantes_error` | Cola con el SRI |
| `sri_webhooks_pendientes`, `sri_correos_pendientes` | Colas de notificaciones |

Regla de alerta sugerida para Prometheus:

```yaml
- alert: SriCaido
  expr: sri_disponible == 0
  for: 5m
- alert: ComprobantesAtascados
  expr: sri_comprobantes_pendiente_antiguedad_segundos > 1800
```

## ATS (Anexo Transaccional Simplificado)

`GET /reportes/ats?ruc=...&anio=2026&mes=10` genera el XML del mes para importar en **DIMM Formularios**:

- **Compras**: cada documento sustento de las **retenciones emitidas** (bases por tarifa, IVA, retenciones de IVA y renta) y las **liquidaciones de compra**.
- **Ventas**: los comprobantes electrónicos no van en el detalle de ventas; se informan los totales por establecimiento (facturas + notas de débito − notas de crédito autorizadas).
- **Anulados**: los electrónicos se anulan en SRI en línea y no se repiten.

> ⚠️ **Revísalo con tu contador antes de presentarlo.** No conseguí el XSD oficial del ATS, así que la estructura sigue la ficha técnica pero solo DIMM la valida de verdad. Además, las facturas de proveedores **sin** retención no pasan por este servicio y hay que agregarlas aparte. `GET /reportes/ats/resumen` lista las advertencias (sustento tributario asumido, autorizaciones faltantes, anulados del mes).

## Seguridad

- Certificados .p12 y sus claves cifrados con AES-256-GCM en la base; la firma se hace en memoria.
- Contraseñas con bcrypt; login con tiempo constante y bloqueo tras 5 fallos en 15 minutos.
- Aislamiento por tenant en cada consulta (probado en tests).
- Auditoría inmutable (tabla protegida por trigger) de logins, usuarios, certificados, emisiones, anulaciones y webhooks.
- Webhooks firmados con HMAC-SHA256; secretos cifrados; protección anti-SSRF.
- Logo validado por su contenido (firma PNG/JPEG), no por la extensión.

## Pruebas

`mvn test` (requiere Docker): firma XAdES-BES válida, clave de acceso, XML de factura, nota de crédito y retención validados contra los **XSD oficiales del SRI** (`src/test/resources/xsd`), cálculos de totales, anulación, sincronización, RIDE de los 6 tipos (texto extraído del PDF), reportes (resumen y CSV con fórmulas neutralizadas), Swagger, webhooks (firma verificada con un receptor real, reintentos, reenvío, anti-SSRF), email con adjuntos, alertas cuando el SRI cae y se recupera, métricas protegidas, ATS (compras, retenciones y ventas por establecimiento), y el flujo completo contra un Postgres real con el SRI simulado: roles, aislamiento entre tenants, rotación de tokens, idempotencia, SRI caído, rechazo y 20 emisiones concurrentes sin secuenciales repetidos.

## Limitaciones conocidas

- Nota de débito, guía de remisión y liquidación de compra no tienen XSD oficial en los tests: su estructura se confirma al emitir con un RUC real en el ambiente de pruebas (el SRI valida el RUC antes que la estructura).
- Retención: solo pagos locales (`pagoLocExt` 01) y sin reembolsos.
- Solo se calcula IVA; ICE, IRBPNR o subsidios requieren el endpoint avanzado `/sri/comprobantes`.

## Ideas para más adelante

- Registrar compras recibidas (facturas de proveedores) para que el ATS salga completo.
- SMTP por tenant (hoy es uno para todo el servicio).
