# Resultados de la medición de la sección 3 del RFC

> Corrida del 2026-11-07 en una laptop, con Postgres 18.6 en Docker y los datos en memoria: sirven como orden
> de magnitud. Salida de [`03-indices.sql`](03-indices.sql) y [`03-precios-y-busqueda.sql`](03-precios-y-busqueda.sql),
> sin las líneas de control de `psql`. Cómo repetirla: RFC, sección 3.5.

## Índices de `orders`

```text
== Datos generados
 ordenes | tabla  
---------+--------
 2010011 | 342 MB

 userid | ordenes | pendientes | ejecutadas_y_pendientes 
--------+---------+------------+-------------------------
   5000 |     100 |          6 |                      92
  10005 |   10000 |          8 |                    8597
  10006 | 1000000 |          8 |                  850237

== Tamaño de cada índice
             indice              | tamanio 
---------------------------------+---------
 orders_user_status_datetime_idx | 95 MB
 orders_user_clientorderid_key   | 78 MB
 orders_user_datetime_idx        | 78 MB
 orders_pkey                     | 43 MB
 orders_user_new_idx             | 2024 kB

== Pendientes del usuario extremo con plan genérico, con el índice de fecha y el parcial
-- 1. con el estado como parámetro: el plan genérico no usa el parcial
                                          QUERY PLAN                                           
 Limit (actual rows=8.00 loops=1)
   Buffers: shared hit=3583 read=23156 written=1
   ->  Index Scan Backward using orders_user_datetime_idx on orders (actual rows=8.00 loops=1)
         Index Cond: (userid = $1)
         Filter: ((status)::text = $2)
         Rows Removed by Filter: 999992
         Index Searches: 1
         Buffers: shared hit=3583 read=23156 written=1
 Planning:
   Buffers: shared hit=18 read=4 dirtied=1
 Planning Time: 0.123 ms
 Execution Time: 127.700 ms

-- 2. con NEW escrito en el SQL: lo usa
                                          QUERY PLAN                                           
 Limit (actual rows=8.00 loops=1)
   Buffers: shared hit=2 read=3
   ->  Index Only Scan Backward using orders_user_new_idx on orders (actual rows=8.00 loops=1)
         Index Cond: (userid = $1)
         Heap Fetches: 8
         Index Searches: 1
         Buffers: shared hit=2 read=3
 Planning Time: 0.090 ms
 Execution Time: 0.047 ms

-- 3. solo con el índice de A-7: sirve aunque el estado sea un parámetro
                                                QUERY PLAN                                                 
 Limit (actual rows=8.00 loops=1)
   Buffers: shared hit=2 read=3
   ->  Index Only Scan Backward using orders_user_status_datetime_idx on orders (actual rows=8.00 loops=1)
         Index Cond: ((userid = $1) AND (status = $2))
         Heap Fetches: 8
         Index Searches: 1
         Buffers: shared hit=2 read=3
 Planning:
   Buffers: shared hit=62 read=1
 Planning Time: 0.221 ms
 Execution Time: 0.032 ms

== Mediana en ms por combinación
                    consulta                     |        usuario        | 0 sin índices | 1 solo R-2 | A elegida | B solo estado | C A + A-7 | D A + parcial 
-------------------------------------------------+-----------------------+---------------+------------+-----------+---------------+-----------+---------------
 1. portfolio: FILLED y NEW en orden cronológico | 1 típico (100)        |        53.797 |      0.031 |     0.017 |         0.048 |     0.024 |         0.016
 1. portfolio: FILLED y NEW en orden cronológico | 2 pesado (10.000)     |        54.868 |      3.237 |     1.218 |         1.490 |     1.478 |         1.194
 1. portfolio: FILLED y NEW en orden cronológico | 3 extremo (1.000.000) |       303.777 |    307.137 |   174.649 |       316.759 |   177.396 |       173.943
 2. listado: primera página                      | 1 típico (100)        |        32.877 |      0.031 |     0.006 |         0.033 |     0.006 |         0.006
 2. listado: primera página                      | 2 pesado (10.000)     |        34.805 |      2.995 |     0.006 |         2.876 |     0.006 |         0.006
 2. listado: primera página                      | 3 extremo (1.000.000) |        91.030 |     91.199 |     0.007 |        83.443 |     0.007 |         0.007
 3. listado: última página (cursor)              | 1 típico (100)        |        40.099 |      0.021 |     0.007 |         0.014 |     0.007 |         0.007
 3. listado: última página (cursor)              | 2 pesado (10.000)     |        40.961 |      1.447 |     0.007 |         0.016 |     0.007 |         0.007
 3. listado: última página (cursor)              | 3 extremo (1.000.000) |        42.000 |     42.614 |     0.007 |         0.020 |     0.008 |         0.007
 4. listado: pendientes (NEW)                    | 1 típico (100)        |        39.863 |      0.017 |     0.012 |         0.005 |     0.005 |         0.004
 4. listado: pendientes (NEW)                    | 2 pesado (10.000)     |        40.123 |      1.472 |     0.715 |         0.006 |     0.005 |         0.005
 4. listado: pendientes (NEW)                    | 3 extremo (1.000.000) |        41.552 |     42.091 |   131.086 |         0.005 |     0.006 |         0.005
 5. listado: rechazadas, primera página          | 1 típico (100)        |        39.840 |      0.017 |     0.012 |         0.005 |     0.004 |         0.012
 5. listado: rechazadas, primera página          | 2 pesado (10.000)     |        39.371 |      1.621 |     0.026 |         0.007 |     0.007 |         0.025
 5. listado: rechazadas, primera página          | 3 extremo (1.000.000) |        47.977 |     47.954 |     0.029 |         0.008 |     0.008 |         0.028
 6. alta de una orden, en microsegundos          | todos                 |               |       12.3 |      19.2 |               |      25.1 |          20.0

== Índices que usó cada consulta del usuario extremo
                    consulta                     |                     config                     |              plan               
-------------------------------------------------+------------------------------------------------+---------------------------------
 1. portfolio: FILLED y NEW en orden cronológico | 0. sin índices (como hoy)                      | tabla entera
 1. portfolio: FILLED y NEW en orden cronológico | 1. solo el único del identificador (R-2)       | tabla entera
 1. portfolio: FILLED y NEW en orden cronológico | A. + (usuario, fecha, id): la elegida          | orders_user_datetime_idx
 1. portfolio: FILLED y NEW en orden cronológico | B. solo (usuario, estado, fecha, id)           | tabla entera
 1. portfolio: FILLED y NEW en orden cronológico | C. A + (usuario, estado, fecha, id), la de A-7 | orders_user_datetime_idx
 1. portfolio: FILLED y NEW en orden cronológico | D. A + parcial de pendientes                   | orders_user_datetime_idx
 2. listado: primera página                      | 0. sin índices (como hoy)                      | tabla entera
 2. listado: primera página                      | 1. solo el único del identificador (R-2)       | tabla entera
 2. listado: primera página                      | A. + (usuario, fecha, id): la elegida          | orders_user_datetime_idx
 2. listado: primera página                      | B. solo (usuario, estado, fecha, id)           | tabla entera
 2. listado: primera página                      | C. A + (usuario, estado, fecha, id), la de A-7 | orders_user_datetime_idx
 2. listado: primera página                      | D. A + parcial de pendientes                   | orders_user_datetime_idx
 3. listado: última página (cursor)              | 0. sin índices (como hoy)                      | tabla entera
 3. listado: última página (cursor)              | 1. solo el único del identificador (R-2)       | tabla entera
 3. listado: última página (cursor)              | A. + (usuario, fecha, id): la elegida          | orders_user_datetime_idx
 3. listado: última página (cursor)              | B. solo (usuario, estado, fecha, id)           | orders_user_status_datetime_idx
 3. listado: última página (cursor)              | C. A + (usuario, estado, fecha, id), la de A-7 | orders_user_datetime_idx
 3. listado: última página (cursor)              | D. A + parcial de pendientes                   | orders_user_datetime_idx
 4. listado: pendientes (NEW)                    | 0. sin índices (como hoy)                      | tabla entera
 4. listado: pendientes (NEW)                    | 1. solo el único del identificador (R-2)       | tabla entera
 4. listado: pendientes (NEW)                    | A. + (usuario, fecha, id): la elegida          | orders_user_datetime_idx
 4. listado: pendientes (NEW)                    | B. solo (usuario, estado, fecha, id)           | orders_user_status_datetime_idx
 4. listado: pendientes (NEW)                    | C. A + (usuario, estado, fecha, id), la de A-7 | orders_user_status_datetime_idx
 4. listado: pendientes (NEW)                    | D. A + parcial de pendientes                   | orders_user_new_idx
 5. listado: rechazadas, primera página          | 0. sin índices (como hoy)                      | tabla entera
 5. listado: rechazadas, primera página          | 1. solo el único del identificador (R-2)       | tabla entera
 5. listado: rechazadas, primera página          | A. + (usuario, fecha, id): la elegida          | orders_user_datetime_idx
 5. listado: rechazadas, primera página          | B. solo (usuario, estado, fecha, id)           | orders_user_status_datetime_idx
 5. listado: rechazadas, primera página          | C. A + (usuario, estado, fecha, id), la de A-7 | orders_user_status_datetime_idx
 5. listado: rechazadas, primera página          | D. A + parcial de pendientes                   | orders_user_datetime_idx

== Altas: corridas
                     config                     | micros_por_orden |                        corridas                        
------------------------------------------------+------------------+--------------------------------------------------------
 1. solo el único del identificador (R-2)       |             12.3 | corridas de 100.000 (ms): 1134, 1236, 1245, 1123, 1229
 A. + (usuario, fecha, id): la elegida          |             19.2 | corridas de 100.000 (ms): 1248, 1910, 1919, 1928, 2052
 C. A + (usuario, estado, fecha, id), la de A-7 |             25.1 | corridas de 100.000 (ms): 2460, 2565, 2523, 2506, 2506
 D. A + parcial de pendientes                   |             20.0 | corridas de 100.000 (ms): 1943, 1997, 2001, 1995, 1986
```

## Último precio, búsqueda y borrado de un activo

```text
== Precios cargados
 filas  | tabla 
--------+-------
 164430 | 12 MB

== Mediana en ms
                          consulta                          |                  config                  |   ms   |                     plan                      
------------------------------------------------------------+------------------------------------------+--------+-----------------------------------------------
 a. último precio de todas las acciones, con DISTINCT ON    | con la regla de unicidad (acción, fecha) | 87.599 | tabla entera
 a. último precio de todas las acciones, con DISTINCT ON    | sin índice                               | 87.811 | tabla entera
 b. último precio de 20 acciones, buscado acción por acción | con la regla de unicidad (acción, fecha) |  0.057 | marketdata_instrument_date_key + tabla entera
 b. último precio de 20 acciones, buscado acción por acción | sin índice                               | 90.530 | tabla entera
 c. búsqueda "pa"                                           | 10.000 activos                           | 14.208 | tabla entera
 c. búsqueda "pa"                                           | 65 activos                               |  0.062 | tabla entera
 d. búsqueda "grupo galicia"                                | 10.000 activos                           | 11.679 | tabla entera
 d. búsqueda "grupo galicia"                                | 65 activos                               |  0.060 | tabla entera
 e. búsqueda "rio de la plata"                              | 10.000 activos                           | 16.895 | tabla entera
 f. búsqueda "y"                                            | 10.000 activos                           | 10.960 | tabla entera

== Borrar un activo que ninguna orden usa
                                       QUERY PLAN                                        
 Delete on instruments (actual rows=0.00 loops=1)
   Buffers: shared hit=7
   ->  Index Scan using instruments_ticker_key on instruments (actual rows=1.00 loops=1)
         Index Cond: ((ticker)::text = 'ZZZZ'::text)
         Index Searches: 1
         Buffers: shared hit=3
 Planning Time: 0.025 ms
 Trigger for constraint orders_instrumentid_fkey: calls=1
 Trigger for constraint marketdata_instrumentid_fkey: calls=1
 Execution Time: 72.550 ms
```
