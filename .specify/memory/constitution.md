# Constitución del Servicio de Facturación Electrónica SRI

## Principios fundamentales

### I. Seguridad primero (NO NEGOCIABLE)

Ante un conflicto entre seguridad, rapidez y comodidad, gana la seguridad.

- Los certificados `.p12`, sus claves y los secretos de webhooks MUST guardarse cifrados
  (AES-256-GCM) y descifrarse solo en memoria; nunca en disco, logs ni respuestas.
- Toda consulta de datos de negocio MUST filtrar por tenant. Acceder a un recurso de otro
  tenant responde 404, sin revelar que existe.
- Las contraseñas MUST guardarse con bcrypt. El login MUST tardar lo mismo exista o no el
  usuario, y limitar los intentos fallidos.
- Los tokens de acceso MUST ser de corta duración, y los refresh tokens rotativos y de un solo
  uso: reutilizar uno revoca toda su sesión.
- Toda entrada externa (JSON, XML, archivos, URLs) MUST validarse en el borde. El XML se parsea
  sin DTD (anti-XXE), las URLs de webhooks se restringen a direcciones públicas (anti-SSRF), los
  archivos se validan por su contenido y no por su extensión, y los CSV neutralizan fórmulas.
- Las acciones sensibles MUST quedar en la auditoría inmutable.

Razón: el servicio firma documentos con valor tributario en nombre de las empresas; una
filtración del certificado permite emitir comprobantes falsos a su nombre.

### II. Ningún comprobante se pierde ni se duplica

- Un comprobante MUST guardarse firmado en la base, junto con su secuencial, en una
  transacción, ANTES de cualquier llamada al SRI.
- El secuencial MUST asignarse de forma atómica: dos emisiones concurrentes nunca comparten
  número.
- Toda emisión MUST aceptar `Idempotency-Key`: reenviar la misma solicitud devuelve el mismo
  comprobante, no uno nuevo.
- Los fallos de red con el SRI, el SMTP o los receptores de webhooks MUST reintentarse solos
  con espera creciente y quedar visibles cuando se agoten los intentos.
- Todo evento derivado de un cambio de estado (webhooks, emails) MUST encolarse en la misma
  transacción que ese cambio.
- Varias instancias del servicio MUST poder procesar la misma cola sin trabajo duplicado
  (reclamo con `FOR UPDATE SKIP LOCKED` o equivalente).

Razón: una factura perdida es un problema tributario; una duplicada es un cobro doble al
cliente. Ambas son más caras que cualquier complejidad que las evite.

### III. Rapidez

- Una emisión MUST intentar el envío al SRI de inmediato, sin esperar al proceso en segundo
  plano; el proceso en segundo plano solo recupera lo que quedó pendiente.
- El servicio MUST responder aunque el SRI esté caído: el comprobante queda PENDIENTE y se
  informa así, sin bloquear al cliente.
- Lo costoso y repetible (cargar el certificado, plantillas) SHOULD cachearse con una clave que
  se invalide sola cuando cambia el dato.
- Listados y reportes MUST paginar o transmitir fila por fila; ninguna respuesta carga datos sin
  límite en memoria.

Razón: el servicio está en el camino crítico de un punto de venta; el cajero no puede esperar.

### IV. Servicio SRI independiente y reutilizable

- La integración con el SRI MUST vivir en este servicio y exponerse solo por HTTP/JSON, para que
  cualquier sistema (Go, React, ERP, etc.) la use sin conocer SOAP, XML ni firma digital.
- La lógica de negocio de cada cliente (productos, inventario, ventas) MUST NOT entrar a este
  servicio; vive en las aplicaciones que lo consumen.
- Los cambios en la API MUST ser compatibles hacia atrás: se agregan campos y endpoints, no se
  quitan ni cambian de significado sin una nueva versión y un aviso a los consumidores conocidos
  (`sri-panel`, `facturacion`).
- Toda funcionalidad nueva MUST quedar documentada en el README (endpoint, rol requerido,
  formato y errores) y en Swagger.

Razón: el objetivo desde el primer día fue una integración que sirva a cualquier sistema futuro.

### V. Simplicidad completa

- Se elige la solución más pequeña que resuelva el problema entero: primero la biblioteca
  estándar y lo ya existente en el proyecto, luego dependencias maduras; nunca una dependencia
  para pocas líneas.
- No se agregan opciones, capas ni configuraciones "por si acaso". Lo que se deja fuera se
  nombra explícitamente.
- Todo atajo con un límite conocido MUST llevar un comentario `shortcut: <límite>, <cuándo
  mejorarlo>`.
- La simplicidad nunca justifica recortar validación, manejo de errores que evita pérdida de
  datos, seguridad ni lo que el usuario pidió.

Razón: menos código es menos superficie de fallos y menos mantenimiento; pero un sistema
incompleto no es simple, está roto.

### VI. Pruebas con evidencia real

- Todo cambio con lógica no trivial (cálculos de dinero, firma, seguridad, concurrencia, colas)
  MUST incluir pruebas que fallarían sin el cambio.
- Las pruebas de integración MUST correr contra un PostgreSQL real (Testcontainers), no contra
  simulaciones de la base. Solo los servicios externos (SRI, SMTP) se simulan.
- El XML generado MUST validarse contra los XSD oficiales del SRI cuando existan. Lo que no se
  puede validar así MUST declararse como no verificado.
- Antes de dar algo por terminado MUST correrse el conjunto completo de pruebas; si algo no se
  pudo verificar (por ejemplo, autorización real sin RUC ni firma reales), se dice
  explícitamente.

Razón: en facturación, "parece funcionar" no basta; los errores aparecen ante el SRI o en una
auditoría.

## Restricciones técnicas y normativas

- **Stack del servicio**: Java 21 LTS, Spring Boot, PostgreSQL con migraciones Flyway que solo
  agregan (nunca se edita una migración ya aplicada). Consumidores: Go + React (`facturacion`) y
  React + TypeScript (`sri-panel`).
- **SRI**: esquema offline, firma XAdES-BES (RSA-SHA1 según la ficha técnica vigente), clave de
  acceso de 49 dígitos con módulo 11, ambiente configurable (1 pruebas, 2 producción). Nada se
  prueba por primera vez en producción.
- **Conservación**: los XML firmados y autorizados MUST conservarse al menos 7 años; la base y la
  `ENCRYPTION_KEY` MUST respaldarse.
- **Despliegue**: contenedor sin root con health check; la API se publica solo detrás de un proxy
  con HTTPS; Swagger se desactiva en producción.
- **Cumplimiento tributario**: lo que el servicio genera para declaraciones (por ejemplo el ATS)
  MUST revisarlo un contador antes de presentarse, y sus supuestos MUST listarse como
  advertencias.

## Flujo de trabajo y calidad

- Idioma: código, documentación, mensajes de la API y comunicación en español.
- Commits separados por funcionalidad, a nombre del autor humano, SIN línea `Co-Authored-By` ni
  atribución de herramientas.
- Nada se sube a un remoto (push) ni se publica sin pedido explícito.
- Antes de cerrar un cambio: compila, pasa el conjunto completo de pruebas, se actualiza el
  README y se informa qué quedó sin verificar.
- Trabajo en paralelo: cada aplicación en su propio repositorio; ninguna sesión modifica un
  repositorio ajeno. Los cambios que una aplicación necesite del servicio se piden, no se hacen
  desde fuera.

## Gobernanza

- Esta constitución prevalece sobre cualquier otra práctica del proyecto. Toda especificación,
  plan y tarea (`/speckit-specify`, `/speckit-plan`, `/speckit-tasks`) MUST verificar que la
  cumple; una desviación MUST justificarse por escrito en el plan.
- Enmiendas: se proponen por escrito con su motivo, las aprueba el dueño del proyecto y se
  registran en un commit propio.
- Versionado semántico: MAJOR si se elimina o redefine un principio; MINOR si se agrega un
  principio o sección o se amplía materialmente; PATCH para aclaraciones de redacción.
- Revisión: en cada revisión de código se comprueba el cumplimiento de los principios I, II y VI
  como mínimo.

**Version**: 1.0.0 | **Ratified**: 2026-11-03 | **Last Amended**: 2026-11-03
