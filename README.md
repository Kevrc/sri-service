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
| POST | `/auth/logout` | público | Revoca el refresh token |
| POST | `/auth/register` | SUPERADMIN, ADMIN | Crear usuario (ADMIN solo en su tenant) |
| PATCH | `/auth/change-password` | todos | Cambia la clave y cierra todas las sesiones |
| POST/GET | `/tenants` | SUPERADMIN | Empresas clientes |
| POST/GET | `/emisores`, `/emisores/{id}` | ADMIN crea, todos leen | RUCs del tenant |
| PUT/GET | `/emisores/{id}/certificado` | ADMIN sube, todos leen | .p12 (se guarda cifrado) y su vigencia |
| PUT | `/emisores/{id}/logo` | ADMIN | Logo del RIDE (multipart `archivo`, PNG/JPEG ≤ 300 KB) |
| POST/GET | `/emisores/{id}/puntos-emision` | ADMIN crea, todos leen | Establecimiento + punto de emisión |
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
| GET | `/sri/verificar/{clave}` | ADMIN, USER | Consulta al SRI ya mismo |
| POST | `/sri/comprobantes/{clave}/reintentar` | ADMIN, USER | Reintenta uno en `ERROR` |
| PATCH | `/sri/comprobantes/{clave}/anular` | ADMIN | Marca ANULADO un AUTORIZADO (`{"motivo": ...}`). **También hay que anularlo en SRI en línea** |
| POST | `/sri/sincronizar` | ADMIN, USER | Procesa ya todos los pendientes del tenant |
| GET | `/catalogos`, `/catalogos/{nombre}` | todos | `impuestos`, `retenciones-iva`, `retenciones-renta`, `formas-pago`, `tipos-identificacion`, `documentos-sustento`, `motivos-traslado`, `tipos-comprobante` |
| POST/GET | `/webhooks` | ADMIN | Registrar URL (`{"url", "eventos"?}`); el `secreto` se muestra solo al crear |
| DELETE | `/webhooks/{id}` | ADMIN | Eliminar |
| GET | `/webhooks/logs` | ADMIN | Historial de entregas: `estado`, `claveAcceso`, `pagina`, `tamano` |
| POST | `/webhooks/logs/{id}/reenviar` | ADMIN | Reintentar una entrega |
| GET | `/reportes/resumen` | ADMIN, USER | `desde`, `hasta` (aaaa-mm-dd, máx. 1 año), `ruc`: cantidades y montos por tipo/estado y ventas netas |
| GET | `/reportes/comprobantes.csv` | ADMIN, USER | Mismo rango + `codDoc`, `estado`, `ruc`: listado para Excel |
| GET | `/status` | público | Health check (DB y disco) |

Todos los `POST .../emitir` y `/sri/comprobantes` aceptan el header `Idempotency-Key`.

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

{"evento":"comprobante.autorizado","claveAcceso":"...","estado":"AUTORIZADO","numeroAutorizacion":"...", ...}
```

- Responde 2xx para confirmar. Si no, se reintenta con esperas crecientes (hasta 10 veces, ~8 horas) y luego queda `FALLIDO` (reenviable).
- El evento se guarda en la misma transacción que el cambio de estado: nunca se pierde.
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

## Seguridad

- Certificados .p12 y sus claves cifrados con AES-256-GCM en la base; la firma se hace en memoria.
- Contraseñas con bcrypt; login con tiempo constante y bloqueo tras 5 fallos en 15 minutos.
- Aislamiento por tenant en cada consulta (probado en tests).
- Auditoría inmutable (tabla protegida por trigger) de logins, usuarios, certificados, emisiones, anulaciones y webhooks.
- Webhooks firmados con HMAC-SHA256; secretos cifrados; protección anti-SSRF.
- Logo validado por su contenido (firma PNG/JPEG), no por la extensión.

## Pruebas

`mvn test` (requiere Docker): firma XAdES-BES válida, clave de acceso, XML de factura, nota de crédito y retención validados contra los **XSD oficiales del SRI** (`src/test/resources/xsd`), cálculos de totales, anulación, sincronización, RIDE de los 6 tipos (texto extraído del PDF), reportes (resumen y CSV con fórmulas neutralizadas), Swagger, webhooks (firma verificada con un receptor real, reintentos, reenvío, anti-SSRF), y el flujo completo contra un Postgres real con el SRI simulado: roles, aislamiento entre tenants, rotación de tokens, idempotencia, SRI caído, rechazo y 20 emisiones concurrentes sin secuenciales repetidos.

## Limitaciones conocidas

- Nota de débito, guía de remisión y liquidación de compra no tienen XSD oficial en los tests: su estructura se confirma al emitir con un RUC real en el ambiente de pruebas (el SRI valida el RUC antes que la estructura).
- Retención: solo pagos locales (`pagoLocExt` 01) y sin reembolsos.
- Solo se calcula IVA; ICE, IRBPNR o subsidios requieren el endpoint avanzado `/sri/comprobantes`.

## Ideas para más adelante

- Envío del RIDE y XML por email al cliente.
- Métricas Prometheus (protegidas) y alertas si el SRI deja de responder.
- ATS (anexo transaccional simplificado) a partir de los comprobantes guardados.
