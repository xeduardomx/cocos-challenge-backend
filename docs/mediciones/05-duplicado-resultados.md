# Resultados de la medición de la consulta del posible duplicado (RFC, sección 5.5)

> Corrida del 2026-11-10 en una PC, con Postgres 18.6 en Docker y los datos en memoria: sirven como orden de magnitud.
> Salida de [`05-duplicado.sql`](05-duplicado.sql), sin las líneas de control de `psql`, sobre los datos de volumen de
> [`perf/seed-volume.sql`](https://github.com/xeduardomx/cocos-challenge-backend/blob/main/perf/seed-volume.sql):
> 2.010.011 órdenes, con un usuario pesado de 10.000.

## Por qué se midió

El control de posibles duplicados agrega una consulta a cada orden aceptada: busca otra orden igual del mismo usuario
en los últimos 5 segundos ([RFC, sección 5.5](../03-rfc.md#55-la-misma-orden-con-otro-identificador)). Con todos los
tests en verde, la medición con volumen mostró que el envío del usuario pesado había empeorado:

| Corrida de `scripts/run-load-test.sh` | Percentil 95 del envío del usuario pesado |
|---|---|
| 2026-11-08 | 61,15 ms |
| 2026-11-09 | 57,13 ms |
| 2026-11-10, antes del control | 56,12 ms |
| 2026-11-10, con el control y `clock_timestamp()` | **72,9 ms** |
| 2026-11-10, con el control y `statement_timestamp()` | **59,58 ms** |

La primera versión de la consulta usaba `clock_timestamp()` como "ahora", igual que la hora de las órdenes (RFC, sección
3.4).

## Qué se midió

La consulta, con los dos relojes, para el usuario de 10.000 órdenes y sin ninguna orden suya en los últimos 5 segundos.
Es el caso normal: el de una orden que no es un duplicado.

| "Ahora" de la ventana | Cómo usa el índice | Filas leídas y descartadas | Páginas leídas | Tiempo en la base |
|---|---|---|---|---|
| `clock_timestamp()` | busca solo por usuario; la hora se revisa después, fila por fila | 10.000 | 271 | 1,2 a 1,4 ms |
| `statement_timestamp()`, la que usa la API | busca por usuario y por hora | 0 | 3 | 0,011 ms |

**Por qué pasa.** `clock_timestamp()` es volátil: puede dar otro valor cada vez que se la llama, también dentro de una
misma consulta. Postgres no puede usar un valor que cambia como límite del recorrido de un índice, así que lo deja
como filtro: lee todas las órdenes del usuario y descarta cada una. `statement_timestamp()` es estable: vale lo mismo
durante toda la consulta, y entonces sí entra en la condición del índice (`Index Cond`).

**Por qué empeoraba tanto el envío.** La consulta corre dentro del turno del usuario (RFC, sección 5.2), y la corrida le
manda al pesado 20 órdenes por segundo. Cada milisegundo de más con el turno tomado lo esperan las que vienen atrás.

La salida es de la tercera de tres corridas seguidas. El tiempo de la forma A fue de 1,201, 1,382 y 1,423 ms; el de la B,
de 0,011, 0,010 y 0,011 ms.

```text
== A. Con clock_timestamp(), que es volátil
Limit (actual rows=0.00 loops=1)
  Buffers: shared hit=271
  ->  Index Scan Backward using orders_user_datetime_idx on orders (actual rows=0.00 loops=1)
        Index Cond: (userid = 10005)
        Filter: ((clientorderid <> '00000000-0000-4000-8000-000000000000'::uuid) AND ((status)::text = ANY ('{FILLED,NEW}'::text[])) AND ((requestfingerprint)::text = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'::text) AND (datetime > (clock_timestamp() - '00:00:05'::interval)))
        Rows Removed by Filter: 10000
        Index Searches: 1
        Buffers: shared hit=271
Planning:
  Buffers: shared hit=131
Planning Time: 0.185 ms
Execution Time: 1.201 ms

== B. Con statement_timestamp(), que es estable: la que usa la API
Limit (actual rows=0.00 loops=1)
  Buffers: shared hit=3
  ->  Index Scan Backward using orders_user_datetime_idx on orders (actual rows=0.00 loops=1)
        Index Cond: ((userid = 10005) AND (datetime > (statement_timestamp() - '00:00:05'::interval)))
        Filter: ((clientorderid <> '00000000-0000-4000-8000-000000000000'::uuid) AND ((status)::text = ANY ('{FILLED,NEW}'::text[])) AND ((requestfingerprint)::text = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'::text))
        Index Searches: 1
        Buffers: shared hit=3
Planning:
  Buffers: shared hit=5
Planning Time: 0.054 ms
Execution Time: 0.011 ms
```

## Cómo quedó cuidado

Un test de integración (`OrderRepositoryIT`, "no lee ninguna orden del usuario de fuera de la ventana") corre la consulta
y cuenta las filas que el recorrido del índice trajo de la tabla: tienen que ser 0. Con `clock_timestamp()` trae las 11
órdenes del usuario 1, y el test falla. Como la tabla de los tests es chica y Postgres elegiría leerla entera, el test
apaga esa opción solo para su transacción.

## Cómo se repite

```bash
docker compose up --build -d
./scripts/wait-for-api.sh
docker compose exec -T postgres psql -U postgres -X -q <perf/seed-volume.sql
docker compose exec -T postgres psql -U postgres -X <docs/mediciones/05-duplicado.sql
docker compose down
```
