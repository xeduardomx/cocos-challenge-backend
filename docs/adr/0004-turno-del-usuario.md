# ADR 0004 — El turno del usuario: un lock sobre su fila de `users`, en `READ COMMITTED`

> Estado: **aceptada** · 2026-11-07, revisada el 2026-11-10 · Se aplica en el [RFC, sección 5.2](../03-rfc.md#52-el-turno-del-usuario)

## Contexto

- Dos órdenes del mismo usuario que llegan juntas pueden gastar la misma plata o vender las mismas acciones (R-1).
  La orden 11 vendió 30 BMA con 20 en la cuenta y dejó la posición en −10, y dos ventas de 15 que llegan juntas sin
  bloqueo dejarían el mismo resultado (D-1). Es el riesgo más serio del challenge, y el primer principio del RFC.
- La regla "no vender más de lo que se tiene" depende de la suma de muchas filas: no puede ser una regla de la base
  (D-1). La API tiene que leer la cuenta, decidir y grabar sin que otra orden del mismo usuario se meta en el medio
  (GEN-1).

## Opciones

**A. Un lock sobre la fila del usuario** (`select ... for update`), en el nivel de aislamiento por defecto de Postgres,
`READ COMMITTED`. Es un bloqueo pesimista: se bloquea antes de leer, y el que llega segundo espera.

- A favor:
  - La segunda orden espera a que la primera confirme y después lee la cuenta ya actualizada.
  - Las órdenes de usuarios distintos no se esperan entre sí.
  - La misma consulta verifica que el usuario exista.
  - Probado en la copia local: dos ventas de 15 BMA teniendo 20 dejan una `FILLED` y otra `REJECTED`, con la
    posición en 5 ([`mediciones/05-resultados.md`](../mediciones/05-resultados.md)).
- En contra: solo funciona en `READ COMMITTED`. En `REPEATABLE READ` la transacción conserva la foto de su primera
  consulta, y la segunda venta, aunque espera su turno, vende lo mismo: la posición vuelve a −10 (probado).

**B. Un lock de Postgres sin fila** (`pg_advisory_xact_lock`). Hace lo mismo, pero no verifica que el usuario exista,
y sus números se comparten con cualquier otro uso de esos locks en la base.

**C. Un bloqueo optimista.** No se bloquea nada al empezar: cada envío lee la cuenta y evalúa, y al grabar se revisa
si otra orden del usuario se adelantó. Si se adelantó, la transacción se deshace y la orden se evalúa de nuevo. Hay
dos formas:

- **Nivel `SERIALIZABLE`:** Postgres detecta el choque y aborta una de las dos transacciones.
- **Una columna `version` en `users`:** el envío lee la versión al empezar y, al grabar, la sube con
  `update users set version = version + 1 where id = :userId and version = :leida`. Si cambia 0 filas, otra orden se
  adelantó.

En contra, las dos: la API tendría que reintentar la orden entera, con un bucle, un máximo de intentos y una orden que
se evalúa dos veces. La columna, además, cambia una tabla de Cocos. Y el optimista conviene cuando bloquear es caro:
acá el turno dura milisegundos y solo frena a las órdenes del mismo usuario. No se probó, porque la opción A resuelve
el caso con una línea.

**D. Un lock en Java.** Solo funciona con una instancia de la API.

**E. Un `UPDATE` condicionado sobre una tabla de saldos y posiciones**
(`set cantidad = cantidad - 15 where cantidad >= 15`), o la regla "cantidad ≥ 0" en esa tabla.

- A favor: es lo más simple cuando hay una fila con el número. El `UPDATE` bloquea esa fila: el segundo espera y
  vuelve a evaluar la condición. Es lo que hace la cancelación, donde la regla mira una sola fila (prueba F).
- En contra: acá esa fila no existe, porque la tenencia y los pesos son la suma de las órdenes. La tabla se descartó
  en el ADR 0003: el mismo dato quedaría en dos lugares, y la regla no se podría crear, porque BMA ya está en −10.

**F. Un solo comando que lee la cuenta y graba la orden** (`insert ... select`), sin turno. Un comando graba todo o
nada, pero no pone los pedidos en fila: dos a la vez calculan la suma con la misma foto, y ninguno espera al otro,
porque cada uno inserta una fila distinta. Probado: las dos ventas quedan `FILLED` y la posición en −10, igual que
sin turno (prueba G).

## Decisión

**A.** El envío empieza su transacción tomando el turno del usuario, en `READ COMMITTED`. Si no lo consigue en 2
segundos (`lock_timeout`, configurable), responde 503 con "intentá de nuevo": reintentar con el mismo identificador
es seguro (ADR 0005).

## Consecuencias

- La transacción del envío no puede pasar a `REPEATABLE READ`. Si alguien la cambiara, el test del caso 1 de GEN
  fallaría (RFC, sección 10.3).
- Las lecturas (portfolio, búsqueda y listado) no toman el turno ni esperan.
- La cancelación tampoco lo toma: solo libera reservas, y es un solo `UPDATE` condicionado (RFC, sección 5.4).
- Cada envío bloquea una sola fila, siempre la de su usuario y antes que nada, así que dos envíos no se pueden trabar
  entre sí.
- Las órdenes de un mismo usuario se procesan de a una. Alcanza de sobra para una persona: el turno dura milisegundos.
- **Cuándo revisarla:** si un mismo usuario tuviera que mandar cientos de órdenes por segundo, por ejemplo una cuenta
  institucional. Ahí convendría un turno por usuario y activo, o una cola de órdenes por usuario.

## Revisión (fase 8, 2026-11-10)

Al repasar la decisión, el autor se preguntó si no alcanzaba con algo más simple: un SQL que lea y grabe en un solo
paso, sin lock. **Se mantiene A.** Las dos formas de esa idea no estaban entre las opciones, y quedaron escritas:

- **La opción E se amplió** con el `UPDATE` condicionado. Sirve cuando la regla mira una sola fila, y por eso la
  cancelación ya lo usa. La plata disponible y la tenencia salen de la suma de muchas filas.
- **La opción F es nueva,** con su prueba (la G de [`mediciones/05-resultados.md`](../mediciones/05-resultados.md)):
  un solo `insert ... select`, sin turno, deja la posición en −10.

El criterio que queda: si la regla mira una sola fila, alcanza con un `UPDATE` condicionado; si mira la suma de
muchas, hace falta el turno.

El mismo día, la opción C se amplió con la otra forma habitual de un bloqueo optimista, una columna `version` en
`users`, y la opción A quedó nombrada como lo que es, un bloqueo pesimista.
