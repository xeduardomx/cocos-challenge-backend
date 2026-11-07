# ADR 0002 — SQL directo con `JdbcClient`, no JPA

> Estado: **aceptada** · 2026-11-07 · Se aplica en el [RFC, sección 2](../03-rfc.md#2-arquitectura)

## Contexto

Las consultas de la API ya se conocen, y su SQL se verificó en el análisis (anexo de
[`01-analisis.md`](../01-analisis.md)):

- **Portfolio:** sumas por acción con condiciones por lado y estado, más el último precio de cada acción (D-3).
- **Búsqueda:** `unaccent`, palabra por palabra, con un orden propio (A-5).
- **Listado:** paginación por cursor sobre la fecha y el id (A-7).
- **Envío:** procesar de a una las órdenes del usuario y verificar lo disponible, en una transacción (R-1).
- **Cancelación:** cambiar el estado en un solo paso, y solo si la orden sigue `NEW` (A-7, decisión 3).

Casi no hay altas, bajas y modificaciones de objetos relacionados: se graban órdenes y se cambia un estado. Además,
las columnas no siguen las convenciones de Java (`instrumentid`, `previousclose`).

## Opciones

**A. SQL directo con `JdbcClient`.** Viene con Spring (desde Spring Framework 6.1). Se escribe el SQL; Spring maneja
las conexiones, los parámetros y las transacciones, y convierte cada fila en un record de Java.

- A favor:
  - El SQL que corre es el que se lee y el que se probó.
  - Se usa Postgres entero: funciones propias como `unaccent` o `DISTINCT ON`, comparaciones de filas para el cursor,
    bloqueos.
  - Nada pasa por atrás: no hay caché, ni carga diferida, ni grabado automático al confirmar.
- En contra: el SQL se escribe a mano, y una columna mal escrita aparece al correr, no al compilar. Se cubre con un
  test de integración por consulta, contra Postgres real (RFC, principio 5).

**B. JPA (Hibernate, con Spring Data JPA).** Las tablas se mapean a objetos y JPA escribe el SQL.

- A favor: rinde cuando casi todo es crear, leer, modificar y borrar objetos relacionados, con poco SQL a mano.
- En contra:
  - Las consultas importantes de esta API terminarían en SQL nativo igual.
  - Agrega comportamiento escondido en el camino más delicado. Lo habitual para cancelar es leer la orden, cambiarla
    en memoria y que JPA la grabe al confirmar. Dos cancelaciones simultáneas pueden leer `NEW` y "ganar" las dos,
    salvo que se configure el control de concurrencia de JPA (por ejemplo, con una columna de versión, que sería un
    cambio al esquema solo para el ORM). El `UPDATE` se puede escribir a mano, pero entonces ya es SQL.
  - Para saber qué SQL sale y cuándo, hay que conocer bien el ciclo de vida de las entidades.

**C. Spring Data JDBC.** Más simple que JPA: sin caché ni carga diferida. Genera el SQL de las altas, bajas y
modificaciones de cada objeto.

- En contra: genera justo lo que esta API casi no usa. Las consultas importantes irían a mano igual, con `@Query`, y
  suma convenciones propias que hay que aprender.

**D. jOOQ.** SQL escrito en Java, con clases generadas desde el esquema: una tabla o una columna mal escrita no
compila.

- A favor: esa es su ventaja real sobre A, que el compilador valida el SQL.
- En contra: hay que generar el código desde el esquema en cada build, con una base levantada o leyendo las
  migraciones. Para unas 10 consultas no se justifica, y es una herramienta más para aprender.

## Decisión

**A.** Cada repository escribe su SQL con `JdbcClient` y convierte las filas en records. Las transacciones se marcan
en los services. El SQL verificado en el análisis es el punto de partida.

## Consecuencias

- Cada consulta tiene su test de integración contra Postgres real, con las migraciones aplicadas (RFC, sección 10).
- Las tablas no las crea un ORM: los cambios de esquema van por migraciones (RFC, sección 3).
- **Cuándo revisarla:** si la API creciera a muchas tablas con altas y bajas simples, JPA o Spring Data JDBC volverían
  a tener sentido para esa parte. Si las consultas se multiplicaran, jOOQ, para que el compilador valide el SQL.
