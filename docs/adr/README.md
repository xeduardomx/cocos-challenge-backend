# ADR: las decisiones grandes

Un archivo corto por decisión, con el contexto, las opciones evaluadas, la decisión y sus consecuencias. Cómo encaja cada
una en la construcción está en el [RFC](../03-rfc.md).

| ADR | Qué se decidió | Qué se descartó |
|---|---|---|
| [0001](0001-capas-por-funcion.md) | Capas agrupadas por función | hexagonal o Clean Architecture; capas agrupadas por tipo |
| [0002](0002-sql-directo-con-jdbcclient.md) | SQL directo con `JdbcClient` | JPA, Spring Data JDBC y jOOQ |
| [0003](0003-posiciones-calculadas.md) | Las posiciones se calculan desde `orders` en cada pedido | una tabla de posiciones; una foto que se recalcula cada tanto o a una fecha |
| [0004](0004-turno-del-usuario.md) | El turno del usuario: un lock sobre su fila de `users`, en `READ COMMITTED` | un lock sin fila, un bloqueo optimista (`SERIALIZABLE` o una columna `version`), un lock en Java, un `UPDATE` condicionado sobre una tabla de posiciones y un solo `insert ... select` |
| [0005](0005-reintentos-con-identificador-y-huella.md) | Un identificador del cliente y la huella del pedido | guardar el pedido en JSON; un identificador de texto libre; el encabezado `Idempotency-Key` |
| [0006](0006-migraciones-con-flyway.md) | Flyway, con `database.sql` como versión 1 | Liquibase y scripts a mano |
| [0007](0007-metricas-con-prometheus-y-grafana.md) | Micrometer, y Prometheus y Grafana en el `docker compose` | New Relic con cuenta propia, un tablero simulado y no tener tablero |
| [0008](0008-openapi-con-springdoc.md) | La especificación OpenAPI, generada desde el código con springdoc | escribirla a mano; no tener una |
