# ADR 0004 — El turno del usuario: un lock sobre su fila de `users`, en `READ COMMITTED`

> Estado: **aceptada** · 2026-11-07 · Se aplica en el [RFC, sección 5.2](../03-rfc.md#52-el-turno-del-usuario)

## Contexto

- Dos órdenes del mismo usuario que llegan juntas pueden gastar la misma plata o vender las mismas acciones (R-1).
  La orden 11, que dejó BMA en −10, es el síntoma exacto de dos ventas sin bloqueo (D-1). Es el riesgo más serio del
  challenge, y el primer principio del RFC.
- La regla "no vender más de lo que se tiene" depende de la suma de muchas filas: no puede ser una regla de la base
  (D-1). La API tiene que leer la cuenta, decidir y grabar sin que otra orden del mismo usuario se meta en el medio
  (GEN-1).

## Opciones

**A. Un lock sobre la fila del usuario** (`select ... for update`), en el nivel de aislamiento por defecto de Postgres,
`READ COMMITTED`.

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

**C. Nivel `SERIALIZABLE`.** Postgres detecta el choque y aborta una de las dos transacciones. La API tendría que
reintentar la orden entera, con más código y una orden que se evalúa dos veces.

**D. Un lock en Java.** Solo funciona con una instancia de la API.

**E. Una tabla de posiciones con la regla "cantidad ≥ 0".** Descartada en el ADR 0003: BMA ya está en −10.

## Decisión

**A.** El envío empieza su transacción tomando el turno del usuario, en `READ COMMITTED`. Si no lo consigue en 2
segundos (`lock_timeout`, configurable), responde 503 con "intentá de nuevo": reintentar con el mismo identificador
es seguro (ADR 0005).

## Consecuencias

- La transacción del envío no puede pasar a `REPEATABLE READ`. Si alguien la cambiara, el test del caso 1 de GEN
  fallaría (RFC, sección 10.3).
- Las lecturas (portfolio, búsqueda y listado) no toman el turno ni esperan.
- La cancelación tampoco lo toma: solo libera reservas, y es un solo `UPDATE` condicionado (sección 5.4).
- Cada envío bloquea una sola fila, siempre la de su usuario y antes que nada, así que dos envíos no se pueden trabar
  entre sí.
- Las órdenes de un mismo usuario se procesan de a una. Alcanza de sobra para una persona: el turno dura milisegundos.
- **Cuándo revisarla:** si un mismo usuario tuviera que mandar cientos de órdenes por segundo, por ejemplo una cuenta
  institucional. Ahí convendría un turno por usuario y activo, o una cola de órdenes por usuario.
