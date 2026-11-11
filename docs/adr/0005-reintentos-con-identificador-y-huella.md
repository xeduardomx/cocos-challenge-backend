# ADR 0005 — Reintentos: identificador del cliente y huella del pedido

> Estado: **aceptada** · 2026-11-07 · Viene de R-2 (`01-decisiones.md`) y se aplica en el
> [RFC, secciones 3.3, 5.3 y 6.5](../03-rfc.md#53-reintentos)

## Contexto

- Se compran 100 PAMP, la respuesta se pierde y la app reintenta: sin protección, son 200 PAMP (R-2). El turno del
  ADR 0004 no lo evita, porque las dos órdenes son válidas una después de la otra.
- R-2 ya decidió que cada orden trae un identificador obligatorio, que pone la app, como el `ClOrdID` de FIX. Si se
  repite con el mismo contenido, se devuelve la orden original; con otro contenido, es un error.
- La orden grabada no alcanza para comparar el contenido. Una orden a mercado guarda el `close` como precio, aunque el
  pedido no traía precio, y una orden por monto guarda la cantidad calculada, no el monto.

## Opciones

**Para comparar el contenido:**

- **A. Una huella del pedido:** un resumen (SHA-256) de sus datos, en una columna.
- **B. El pedido tal cual, en JSON.** Se lee mejor, pero copia el activo, el lado y el tipo, que ya están en la orden
  (RFC, principio 4), y guarda el monto, que A-6 decidió no guardar.

**Para el formato del identificador:**

- **UUID:** lo genera la app y tiene largo fijo.
- **Texto libre:** más flexible, pero obliga a fijar un largo y qué caracteres valen, y "abc" y "ABC" serían dos
  identificadores distintos.

**Para dónde viaja:**

- **En el cuerpo,** como dato de la orden.
- **En un encabezado `Idempotency-Key`,** como propone un borrador del IETF para repetir cualquier pedido.

## Decisión

- **`clientOrderId`, un UUID, en el cuerpo:** es un dato de la orden, que se graba, vuelve en la respuesta y en el
  listado, y le sirve al cliente para encontrar su orden. Es único por usuario y no vence, porque vive en la orden.
- **Huella `v1`:** SHA-256 de `v1|activo|lado|tipo|cantidad|monto|precio`, con los números sin ceros de más, así
  `10000` y `10000.00` dan la misma.
- **Se resuelve dentro del turno del usuario:** se busca la orden por el identificador. Si no existe, se evalúa y se
  graba (201). Si existe con la misma huella, se devuelve tal cual, sin volver a evaluarla (200). Si existe con otra
  huella, 422 y no se graba nada.
- **La regla de unicidad (usuario, identificador) queda como red:** si la API recibe un error de clave duplicada, lo
  trata como un reintento.

## Consecuencias

- Probado en la copia local: dos pedidos simultáneos con el mismo identificador crean una sola orden. Con el turno, el
  segundo encuentra al primero; sin el turno, lo frena la regla de unicidad
  ([`mediciones/05-resultados.md`](../mediciones/05-resultados.md)).
- Un pedido que da error (400) no graba nada, así que no gasta el identificador.
- Si el cliente genera un identificador nuevo en cada reintento, la protección no funciona. Es un error del cliente
  que la API no puede arreglar del todo, porque no distingue ese pedido de una orden nueva (R-2). Sí lo puede mitigar:
  el [ADR 0009](0009-ordenes-iguales-con-otro-identificador.md) agrega una segunda red, que frena la orden igual que
  llega en los primeros 5 segundos (revisado en la fase 8, 2026-11-10).
- El `v1` de la huella permite cambiar la forma más adelante sin confundir huellas viejas con nuevas.
- **Cuándo revisarla:** si los identificadores tuvieran que vencer, por ejemplo, para no guardarlos para siempre con
  mucho volumen. Ahí pasarían a una tabla propia con vencimiento, como hace Stripe con sus claves.
