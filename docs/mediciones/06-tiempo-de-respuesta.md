# Tiempo de respuesta con volumen

> Corrida del 2026-11-08 en una computadora de escritorio, con la API, Postgres 18.6, Prometheus, Grafana y k6 2.3.0 en
> Docker en la misma máquina, y los datos en memoria: sirven como orden de magnitud. Es la salida de
> [`scripts/run-load-test.sh`](https://github.com/xeduardomx/cocos-challenge-backend/blob/main/scripts/run-load-test.sh), que carga
> [`perf/seed-volume.sql`](https://github.com/xeduardomx/cocos-challenge-backend/blob/main/perf/seed-volume.sql) y corre [`perf/load-test.js`](https://github.com/xeduardomx/cocos-challenge-backend/blob/main/perf/load-test.js) con k6.
>
> **Medida de nuevo el 2026-11-10,** cuando la API sumó el control de posibles duplicados: el objetivo se sigue
> cumpliendo, y el resultado está en ["Con el control de posibles duplicados"](#con-el-control-de-posibles-duplicados-2026-11-10).

## Qué se midió y con qué objetivo

El objetivo es el de la sección 8.4 del RFC (GEN-8): **percentil 95 por debajo de 100 ms en las cinco funciones,** con la
API y la base locales, 2 millones de órdenes en la tabla y usuarios de hasta 10.000 órdenes.

Se mide cada función por separado, para un usuario típico (100 órdenes) y para el pesado (10.000): son nueve
escenarios, cada uno con su umbral `p(95)<100` (la búsqueda no depende del usuario). El número es `http_req_duration`
de k6: desde que se manda el pedido hasta que llega la respuesta completa, sin contar la conexión.

| Escenario | Qué hace cada iteración |
|---|---|
| `search` | Busca un texto, rotando entre cinco (`pamp`, `banco`, `gas`, `ypf`, `holding`) |
| `portfolio_typical`, `portfolio_heavy` | Pide el portfolio de un usuario típico al azar, o del pesado |
| `submit_typical`, `submit_heavy` | Compra a mercado 1 acción de METR ($229,50), y la compra tiene que quedar `FILLED`. Cada envío lleva su propio `clientOrderId`. Desde el 2026-11-10 la compra va por monto, con centavos que rotan: ver "Con el control de posibles duplicados" |
| `list_typical`, `list_heavy` | Pide la primera página del listado, y la mitad de las veces con el filtro `status=NEW`, la consulta de listado más cara según el RFC 3.5 |
| `cancel_typical`, `cancel_heavy` | Manda una orden `LIMIT`, que queda `NEW`, y la cancela. **El umbral mide solo el pedido de cancelar:** va sobre la etiqueta `step:cancel` |

**No es una prueba de capacidad.** Mide cuánto tarda la API con una carga moderada y constante, no cuántos pedidos
aguanta. Cada escenario manda 10 pedidos por segundo durante 60 segundos, con `constant-arrival-rate`: k6 mantiene ese
ritmo aunque la API tarde más en contestar, así el número no depende de lo rápido que conteste. Sumando los escenarios
son 110 pedidos por segundo (la cancelación necesita dos pedidos). Los valores están en constantes al principio de
`perf/load-test.js`, y se pueden pisar con variables de entorno.

**Primero, 30 segundos de calentamiento.** La JVM recién arrancada es lenta en los primeros pedidos, así que antes de
medir un escenario sin umbral recorre las cinco funciones. Después hay una pausa de 5 segundos, y recién ahí arrancan los
nueve escenarios medidos, al mismo tiempo.

**Un umbral sin pedidos no prueba nada.** Si el filtro de un umbral no encuentra pedidos, k6 lo da por cumplido sin datos
(se comprobó: con un filtro inexistente, `p(95)<100` pasa con `p(95)=0s`). Por eso, además del umbral de tiempo, cada
escenario exige que haya pedidos (`http_reqs: count>0`), que todas las respuestas sean las esperadas (`checks: rate==1`,
que también falla si una compra queda `REJECTED`) y que k6 haya podido mandar todos los pedidos previstos
(`dropped_iterations: count==0`).

## La máquina

Una computadora de escritorio, con todo en la misma máquina: k6, la API, Postgres, Prometheus y Grafana, en Docker.

| | |
|---|---|
| CPU | Intel Core i7-12700F, 12 núcleos (8 de rendimiento y 4 de eficiencia), 20 hilos |
| Memoria | 31 GB |
| Docker | 28.1.1, con Compose 2.35.1 |

La API y Postgres corren con los valores por defecto del `docker-compose.yml`, sin límites de CPU ni memoria, y los
datos de Postgres viven en memoria (`tmpfs`).

## Los datos y la carga

Los datos son los del RFC 3.5, armados con [`perf/seed-volume.sql`](https://github.com/xeduardomx/cocos-challenge-backend/blob/main/perf/seed-volume.sql) sobre el esquema ya
migrado: 2.010.011 órdenes, 342 MB de tabla.

| Usuario | Id | Órdenes | Ejecutadas y pendientes (las que lee la cuenta) |
|---|---|---|---|
| Típicos (10.000) | 5 a 10004 | 100 cada uno | unas 86, de 72 a 98; 92 en el usuario 5000 del RFC 3.5 |
| Pesado | 10005 | 10.000 | 8.597 |
| Extremo | 10006 | 1.000.000 | 850.237 |

La semilla sale de la parte de datos de [`03-indices.sql`](03-indices.sql), con `setseed(0.2026)`: la misma secuencia
al azar, y por eso la misma cantidad de órdenes y de pendientes del RFC 3.5. La carga verifica los totales al final y,
si no dan, la corrida se corta. Antes de cargar borra los dos índices de la tabla, al final los vuelve a crear con los
nombres de las migraciones V4 y V6, y termina con `vacuum analyze orders`.

**El depósito inicial es de 100.000.000, no el 1.000.000 del RFC 3.5.** Las órdenes de la semilla son al azar y no miran el
saldo, y con un millón no les alcanza. En una consulta aparte, sobre la semilla con ese depósito, el 38 % de los
típicos (3.825 de 10.000) y el pesado (−$57.927.782 disponibles) no pueden comprar ni una acción de METR. Una compra rechazada no prueba el camino completo.
Medido con ese depósito, en una corrida corta (15 segundos más el calentamiento): de las compras a mercado, el 37 % de
las del típico y todas las del pesado quedaron `REJECTED`, y las órdenes `LIMIT` del pesado también, así que no había
nada para cancelar. Con el depósito nuevo, todas se ejecutan (ver "Cómo terminaron las órdenes"). Cambia solo ese
número: la secuencia al azar es la misma, y las demás filas no cambian.

## Cómo repetirlo

```bash
./scripts/run-load-test.sh
```

Levanta todo de cero con `docker compose`, espera a la API, carga la semilla, corre k6, y antes de bajar todo muestra
cómo terminaron las órdenes y el tiempo del usuario extremo. Termina con el código de k6: 0 si se cumplen todos los
umbrales. Si el puerto 5433 está ocupado, `POSTGRES_PORT=5434 ./scripts/run-load-test.sh`.

Para que se vea que el umbral falla cuando tiene que fallar, `P95_LIMIT_MS=1 ./scripts/run-load-test.sh` termina con 99,
el código de k6 cuando no se cumple un umbral.

**Línea de base,** sobre los datos originales y sin la semilla, con el usuario 1 como único usuario (el único con plata):

```bash
docker compose up --build -d
./scripts/wait-for-api.sh
docker run --rm --network cocos-challenge_default -v "$PWD/perf:/perf:ro" \
  -e API_URL=http://api:8080 -e TYPICAL_USER_MIN_ID=1 -e TYPICAL_USER_MAX_ID=1 -e HEAVY_USER_ID=1 \
  grafana/k6:2.3.0 run --no-usage-report --quiet /perf/load-test.js
docker compose down
```

## Resultado con volumen

k6 terminó con código 0, con los nueve umbrales de tiempo y los demás cumplidos. En cada escenario, pedidos y tiempos en milisegundos:

| Escenario | Pedidos | p50 (ms) | p95 (ms) | p99 (ms) | Máximo (ms) | p95 de la corrida anterior (ms) |
|---|---|---|---|---|---|---|
| `search` | 600 | 3,06 | **4,53** | 5,45 | 33,33 | 4,59 |
| `portfolio_typical` | 601 | 5,65 | **6,94** | 7,9 | 9 | 6,82 |
| `portfolio_heavy` | 600 | 27,57 | **35,01** | 37,82 | 40,88 | 34,23 |
| `submit_typical` | 601 | 6,38 | **8,32** | 9,8 | 12,46 | 8,42 |
| `submit_heavy` | 601 | 32,14 | **61,15** | 70,28 | 72,54 | 59,91 |
| `list_typical` | 601 | 2,97 | **4,52** | 5,31 | 31,04 | 4,48 |
| `list_heavy` | 601 | 4,18 | **8,31** | 9,15 | 11,12 | 8,21 |
| `cancel_typical` (solo el pedido de cancelar) | 601 | 1,87 | **3,1** | 4,1 | 5,24 | 3,12 |
| `cancel_heavy` (solo el pedido de cancelar) | 601 | 1,49 | **3,09** | 3,69 | 4,47 | 3,2 |

La última columna es el percentil 95 de una corrida anterior con la misma carga y los mismos datos. Entre las dos
corridas, la mayor diferencia es de 1,24 ms, en `submit_heavy`.

El envío del usuario pesado es el más lento, con 61,15 ms de percentil 95: calcula la cuenta leyendo sus 8.597 órdenes
ejecutadas y pendientes (más las que le va sumando la corrida), y además tiene 20 envíos por segundo a la vez sobre la
misma cuenta (compras y órdenes `LIMIT` de la cancelación), que se atienden de a uno por el turno del usuario (RFC 5.2).
El portfolio del pesado lee las mismas órdenes, sin turno: 35,01 ms. En la base, esa consulta tarda 1,2 ms (RFC 3.5); el
resto es traer las filas, calcular en Java, armar el JSON y la red.

## Línea de base

Sobre los datos originales, sin la semilla, con el mismo `perf/load-test.js` y la misma carga. El usuario 1 (el único con
plata) hace de usuario típico y de pesado. k6 terminó con código 0.

| Escenario | Pedidos | p50 (ms) | p95 (ms) | p99 (ms) | Máximo (ms) |
|---|---|---|---|---|---|
| `search` | 601 | 3,32 | **4,96** | 7,56 | 19,67 |
| `portfolio_typical` | 601 | 6,98 | **10,23** | 12,21 | 22,39 |
| `portfolio_heavy` | 601 | 6,9 | **9,96** | 12,21 | 21,89 |
| `submit_typical` | 600 | 17,65 | **35,11** | 41,06 | 43,99 |
| `submit_heavy` | 600 | 17,71 | **34,07** | 39,65 | 44,34 |
| `list_typical` | 601 | 3,8 | **6,44** | 7,6 | 23,4 |
| `list_heavy` | 600 | 3,83 | **6,61** | 7,26 | 14,21 |
| `cancel_typical` (solo el pedido de cancelar) | 601 | 2,33 | **3,66** | 4,59 | 5,96 |
| `cancel_heavy` (solo el pedido de cancelar) | 601 | 2,31 | **3,49** | 4,16 | 7,2 |

El envío tarda más que el de los típicos con volumen (35,11 y 34,07 ms de percentil 95 contra 8,32 ms) porque acá los 40
envíos por segundo van todos a la misma cuenta y se turnan: la cuenta empieza con pocas órdenes, pero cada envío espera
a los otros.

## Cómo terminaron las órdenes

Órdenes que creó la corrida con volumen, incluido el calentamiento (`orders` con `id` mayor a la última de la semilla):

```text
== Orders created by this run, by kind of user, type and final status (warm-up included)
 user_kind |  type  |  status   | orders 
-----------+--------+-----------+--------
 heavy     | LIMIT  | CANCELLED |    752
 heavy     | MARKET | FILLED    |    752
 typical   | LIMIT  | CANCELLED |    752
 typical   | MARKET | FILLED    |    752
(4 rows)
```

Ninguna compra quedó rechazada: las 752 compras de los típicos y las 752 del pesado se ejecutaron, y las
1.504 órdenes `LIMIT` se cancelaron. En la línea de base, lo mismo: 1.502 compras `FILLED` y 1.504 órdenes `LIMIT`
canceladas.

## El usuario extremo

Fuera del objetivo, como límite documentado (A-4): su portfolio lee 850.237 órdenes ejecutadas y pendientes. Cinco
pedidos sueltos de su portfolio, en esta corrida y con la JVM ya caliente, en segundos:
**0,792, 0,825, 0,661, 0,942 y 0,678.** Son entre 6,6 y 9,4 veces el objetivo de 100 ms. La salida prevista es la
foto a una fecha (A-4).

## Conclusión

**El objetivo del RFC 8.4 se cumple:** con 2.010.011 órdenes en la tabla, el percentil 95 de las cinco funciones queda
por debajo de 100 ms, para usuarios típicos y para el pesado de 10.000 órdenes. El peor es el envío del pesado, 61,15 ms; el
siguiente, su portfolio, 35,01 ms; el resto, 8,32 ms o menos.

Los límites de esta medición: es una carga moderada de 110 pedidos por segundo durante un minuto, sobre una computadora de
escritorio, con la base en memoria y k6 compartiendo la máquina con la API; y no dice cuántos pedidos aguanta la API, ni
cómo se comporta por encima de esa carga. El usuario extremo no entra en el objetivo y queda documentado arriba.

## Con el control de posibles duplicados (2026-11-10)

En la fase 8 la API sumó el control de posibles duplicados: una orden igual a otra aceptada del mismo usuario hace
menos de 5 segundos, con otro identificador, se frena con 409
([RFC, sección 5.5](../03-rfc.md#55-la-misma-orden-con-otro-identificador)). Para la medición cambiaron dos cosas.

**1. El script: ahora cada orden es distinta.** Antes, cada escenario mandaba siempre la misma compra, 10 veces por
segundo. Con el control casi todas se frenarían. Se comprobó con el script anterior, en una corrida corta de la línea de
base: quedó `FILLED` el 1 % de las compras, y el resto recibió el 409.

| Orden | Antes | Ahora |
|---|---|---|
| Compra a mercado | 1 acción de METR, por cantidad | por monto, de $230,00 a $429,99: siempre alcanza para 1 acción de METR ($229,50) y no para 2, así que gasta lo mismo que antes |
| Orden `LIMIT` de la cancelación | 1 acción a $100 | 1 acción, a un precio de $10,00 a $209,99 |

Los centavos salen del escenario y del número de iteración. Cada escenario tiene un rango propio, porque en la línea de
base un solo usuario recibe a la vez las órdenes de todos. Dentro de su rango, un escenario repite una orden recién a
los 100 segundos: 1.000 iteraciones, a 10 por segundo.

**2. La consulta nueva, corregida con esta medición.** La primera versión del control comparaba la hora contra
`clock_timestamp()`. La corrida terminó con código 0, pero el envío del usuario pesado subió a 72,9 ms de percentil 95.
Esa función es volátil, y Postgres leía las 10.000 órdenes del usuario en vez de usar el índice para la ventana. Con
`statement_timestamp()` lee solo los últimos 5 segundos. El detalle está en
[`05-duplicado-resultados.md`](05-duplicado-resultados.md).

**Resultado con volumen,** con la consulta corregida. k6 terminó con código 0, con los nueve umbrales de tiempo y los
demás cumplidos:

<!-- --8<-- [start:resultado-volumen] -->

| Escenario | Pedidos | p50 (ms) | p95 (ms) | p99 (ms) | Máximo (ms) | p95 del 2026-11-08, sin el control (ms) |
|---|---|---|---|---|---|---|
| `search` | 601 | 3,03 | **4,79** | 8 | 25,44 | 4,53 |
| `portfolio_typical` | 600 | 5,51 | **7,12** | 8,17 | 24,3 | 6,94 |
| `portfolio_heavy` | 600 | 24,7 | **33,91** | 36,23 | 42,36 | 35,01 |
| `submit_typical` | 601 | 6,61 | **8,94** | 11,68 | 15 | 8,32 |
| `submit_heavy` | 601 | 29,18 | **59,58** | 68,88 | 72,46 | 61,15 |
| `list_typical` | 601 | 2,89 | **4,65** | 5,25 | 23,12 | 4,52 |
| `list_heavy` | 600 | 4,07 | **8,39** | 9,53 | 10,59 | 8,31 |
| `cancel_typical` (solo el pedido de cancelar) | 601 | 1,7 | **3,14** | 3,83 | 4,44 | 3,1 |
| `cancel_heavy` (solo el pedido de cancelar) | 600 | 1,18 | **2,96** | 3,67 | 4,81 | 3,09 |

<!-- --8<-- [end:resultado-volumen] -->

El envío del usuario pesado, por versión de la consulta:

| Corrida | p50 (ms) | p95 (ms) |
|---|---|---|
| 2026-11-08, sin el control | 32,14 | 61,15 |
| 2026-11-10, con el control y `clock_timestamp()` | 37,24 | 72,9 |
| 2026-11-10, con el control y `statement_timestamp()` | 29,18 | **59,58** |

**Línea de base,** con el usuario 1 como único usuario. k6 terminó con código 0:

| Escenario | Pedidos | p50 (ms) | p95 (ms) | p99 (ms) | Máximo (ms) |
|---|---|---|---|---|---|
| `search` | 601 | 3,31 | **4,87** | 6,05 | 12,17 |
| `portfolio_typical` | 601 | 6,65 | **10** | 11,28 | 35,14 |
| `portfolio_heavy` | 600 | 6,64 | **9,91** | 11,22 | 37 |
| `submit_typical` | 601 | 16,8 | **35,15** | 41,05 | 43,47 |
| `submit_heavy` | 601 | 14,33 | **35,04** | 40,52 | 49,52 |
| `list_typical` | 600 | 3,63 | **6,51** | 7,39 | 37,69 |
| `list_heavy` | 601 | 3,63 | **6,31** | 7,33 | 9,27 |
| `cancel_typical` (solo el pedido de cancelar) | 600 | 2,01 | **3,49** | 3,95 | 8,47 |
| `cancel_heavy` (solo el pedido de cancelar) | 601 | 2,1 | **3,46** | 4,11 | 8,45 |

**Cómo terminaron las órdenes.** Ninguna se frenó como posible duplicado ni quedó rechazada: todas las compras se
ejecutaron y todas las órdenes `LIMIT` se cancelaron. En la línea de base, 1.504 compras `FILLED` y 1.503 órdenes `LIMIT`
canceladas, sin ninguna respuesta 409.

```text
== Orders created by this run, by kind of user, type and final status (warm-up included)
 user_kind |  type  |  status   | orders 
-----------+--------+-----------+--------
 heavy     | LIMIT  | CANCELLED |    750
 heavy     | MARKET | FILLED    |    751
 typical   | LIMIT  | CANCELLED |    751
 typical   | MARKET | FILLED    |    751
(4 rows)
```

**El usuario extremo,** en esta corrida: 0,790, 0,813, 0,773, 0,718 y 0,706 segundos.

**El objetivo se sigue cumpliendo.** Con el control, el peor percentil 95 sigue siendo el envío del usuario pesado:
59,58 ms, dentro de lo que ya variaba de una corrida a otra (61,15, 57,13 y 56,12 ms en las tres anteriores).

## Salida de la corrida con volumen

Lo que imprimió `scripts/run-load-test.sh` en la corrida del 2026-11-08, sin las líneas de `docker compose`.

```text
== Volume data
 orders  | table_size 
---------+------------
 2010011 | 342 MB

 userid | orders  | pending | filled_and_pending 
--------+---------+---------+--------------------
   5000 |     100 |       6 |                 92
  10005 |   10000 |       8 |               8597
  10006 | 1000000 |       8 |             850237

  █ THRESHOLDS 

    checks{scenario:cancel_heavy}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:cancel_typical}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:list_heavy}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:list_typical}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:portfolio_heavy}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:portfolio_typical}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:search}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:submit_heavy}
    ✓ 'rate==1' rate=100.00%

    checks{scenario:submit_typical}
    ✓ 'rate==1' rate=100.00%

    dropped_iterations{scenario:cancel_heavy}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:cancel_typical}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:list_heavy}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:list_typical}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:portfolio_heavy}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:portfolio_typical}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:search}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:submit_heavy}
    ✓ 'count==0' count=0

    dropped_iterations{scenario:submit_typical}
    ✓ 'count==0' count=0

    http_req_duration{scenario:cancel_heavy,step:cancel}
    ✓ 'p(95)<100' p(95)=3.09ms

    http_req_duration{scenario:cancel_typical,step:cancel}
    ✓ 'p(95)<100' p(95)=3.1ms

    http_req_duration{scenario:list_heavy}
    ✓ 'p(95)<100' p(95)=8.31ms

    http_req_duration{scenario:list_typical}
    ✓ 'p(95)<100' p(95)=4.52ms

    http_req_duration{scenario:portfolio_heavy}
    ✓ 'p(95)<100' p(95)=35.01ms

    http_req_duration{scenario:portfolio_typical}
    ✓ 'p(95)<100' p(95)=6.94ms

    http_req_duration{scenario:search}
    ✓ 'p(95)<100' p(95)=4.53ms

    http_req_duration{scenario:submit_heavy}
    ✓ 'p(95)<100' p(95)=61.15ms

    http_req_duration{scenario:submit_typical}
    ✓ 'p(95)<100' p(95)=8.32ms

    http_reqs{scenario:cancel_heavy,step:cancel}
    ✓ 'count>0' count=601

    http_reqs{scenario:cancel_typical,step:cancel}
    ✓ 'count>0' count=601

    http_reqs{scenario:list_heavy}
    ✓ 'count>0' count=601

    http_reqs{scenario:list_typical}
    ✓ 'count>0' count=601

    http_reqs{scenario:portfolio_heavy}
    ✓ 'count>0' count=600

    http_reqs{scenario:portfolio_typical}
    ✓ 'count>0' count=601

    http_reqs{scenario:search}
    ✓ 'count>0' count=600

    http_reqs{scenario:submit_heavy}
    ✓ 'count>0' count=601

    http_reqs{scenario:submit_typical}
    ✓ 'count>0' count=601


  █ TOTAL RESULTS 

    checks_total.......: 8270    86.986112/s
    checks_succeeded...: 100.00% 8270 out of 8270
    checks_failed......: 0.00%   0 out of 8270

    ✓ status is 200
    ✓ the purchase is FILLED
    ✓ the limit order stays NEW

    HTTP
    http_req_duration...........................: count=8270 med=5.62ms  p(95)=39.36ms p(99)=58.04ms max=72.54ms 
      { expected_response:true }................: count=8270 med=5.62ms  p(95)=39.36ms p(99)=58.04ms max=72.54ms 
      { scenario:cancel_heavy,step:cancel }.....: count=601  med=1.49ms  p(95)=3.09ms  p(99)=3.69ms  max=4.47ms  
      { scenario:cancel_typical,step:cancel }...: count=601  med=1.87ms  p(95)=3.1ms   p(99)=4.1ms   max=5.24ms  
      { scenario:list_heavy }...................: count=601  med=4.18ms  p(95)=8.31ms  p(99)=9.15ms  max=11.12ms 
      { scenario:list_typical }.................: count=601  med=2.97ms  p(95)=4.52ms  p(99)=5.31ms  max=31.04ms 
      { scenario:portfolio_heavy }..............: count=600  med=27.57ms p(95)=35.01ms p(99)=37.82ms max=40.88ms 
      { scenario:portfolio_typical }............: count=601  med=5.65ms  p(95)=6.94ms  p(99)=7.9ms   max=9ms     
      { scenario:search }.......................: count=600  med=3.06ms  p(95)=4.53ms  p(99)=5.45ms  max=33.33ms 
      { scenario:submit_heavy }.................: count=601  med=32.14ms p(95)=61.15ms p(99)=70.28ms max=72.54ms 
      { scenario:submit_typical }...............: count=601  med=6.38ms  p(95)=8.32ms  p(99)=9.8ms   max=12.46ms 
    http_req_failed.............................: 0.00%  0 out of 8270
    http_reqs...................................: 8270   86.986112/s
      { scenario:cancel_heavy,step:cancel }.....: 601    6.321482/s
      { scenario:cancel_typical,step:cancel }...: 601    6.321482/s
      { scenario:list_heavy }...................: 601    6.321482/s
      { scenario:list_typical }.................: 601    6.321482/s
      { scenario:portfolio_heavy }..............: 600    6.310963/s
      { scenario:portfolio_typical }............: 601    6.321482/s
      { scenario:search }.......................: 600    6.310963/s
      { scenario:submit_heavy }.................: 601    6.321482/s
      { scenario:submit_typical }...............: 601    6.321482/s

    EXECUTION
    dropped_iterations..........................: 0      0/s
      { scenario:cancel_heavy }.................: 0      0/s
      { scenario:cancel_typical }...............: 0      0/s
      { scenario:list_heavy }...................: 0      0/s
      { scenario:list_typical }.................: 0      0/s
      { scenario:portfolio_heavy }..............: 0      0/s
      { scenario:portfolio_typical }............: 0      0/s
      { scenario:search }.......................: 0      0/s
      { scenario:submit_heavy }.................: 0      0/s
      { scenario:submit_typical }...............: 0      0/s
    iteration_duration..........................: count=5558 med=7.28ms  p(95)=55.23ms p(99)=93.69ms max=209.39ms
    iterations..................................: 5558   58.460558/s
    vus.........................................: 0      min=0         max=0 
    vus_max.....................................: 50     min=50        max=50

    NETWORK
    data_received...............................: 27 MB  281 kB/s
    data_sent...................................: 1.3 MB 13 kB/s

== Orders created by this run, by kind of user, type and final status (warm-up included)
 user_kind |  type  |  status   | orders 
-----------+--------+-----------+--------
 heavy     | LIMIT  | CANCELLED |    752
 heavy     | MARKET | FILLED    |    752
 typical   | LIMIT  | CANCELLED |    752
 typical   | MARKET | FILLED    |    752
(4 rows)

== Portfolio of the extreme user (5 loose requests), seconds each
0.791682
0.825013
0.660578
0.941621
0.678443
k6 exit code: 0
```

## Resumen de k6 de la línea de base

```text
  █ TOTAL RESULTS 

    checks_total.......: 8269    87.020484/s
    checks_succeeded...: 100.00% 8269 out of 8269
    checks_failed......: 0.00%   0 out of 8269

    ✓ status is 200
    ✓ the purchase is FILLED
    ✓ the limit order stays NEW

    HTTP
    http_req_duration...........................: count=8269 med=5.06ms  p(95)=28.12ms p(99)=36.44ms max=44.34ms 
      { expected_response:true }................: count=8269 med=5.06ms  p(95)=28.12ms p(99)=36.44ms max=44.34ms 
      { scenario:cancel_heavy,step:cancel }.....: count=601  med=2.31ms  p(95)=3.49ms  p(99)=4.16ms  max=7.2ms   
      { scenario:cancel_typical,step:cancel }...: count=601  med=2.33ms  p(95)=3.66ms  p(99)=4.59ms  max=5.96ms  
      { scenario:list_heavy }...................: count=600  med=3.83ms  p(95)=6.61ms  p(99)=7.26ms  max=14.21ms 
      { scenario:list_typical }.................: count=601  med=3.8ms   p(95)=6.44ms  p(99)=7.6ms   max=23.4ms  
      { scenario:portfolio_heavy }..............: count=601  med=6.9ms   p(95)=9.96ms  p(99)=12.21ms max=21.89ms 
      { scenario:portfolio_typical }............: count=601  med=6.98ms  p(95)=10.23ms p(99)=12.21ms max=22.39ms 
      { scenario:search }.......................: count=601  med=3.32ms  p(95)=4.96ms  p(99)=7.56ms  max=19.67ms 
      { scenario:submit_heavy }.................: count=600  med=17.71ms p(95)=34.07ms p(99)=39.65ms max=44.34ms 
      { scenario:submit_typical }...............: count=600  med=17.65ms p(95)=35.11ms p(99)=41.06ms max=43.99ms 
    http_req_failed.............................: 0.00%  0 out of 8269
    http_reqs...................................: 8269   87.020484/s
      { scenario:cancel_heavy,step:cancel }.....: 601    6.324744/s
      { scenario:cancel_typical,step:cancel }...: 601    6.324744/s
      { scenario:list_heavy }...................: 600    6.314221/s
      { scenario:list_typical }.................: 601    6.324744/s
      { scenario:portfolio_heavy }..............: 601    6.324744/s
      { scenario:portfolio_typical }............: 601    6.324744/s
      { scenario:search }.......................: 601    6.324744/s
      { scenario:submit_heavy }.................: 600    6.314221/s
      { scenario:submit_typical }...............: 600    6.314221/s

    EXECUTION
    dropped_iterations..........................: 0      0/s
      { scenario:cancel_heavy }.................: 0      0/s
      { scenario:cancel_typical }...............: 0      0/s
      { scenario:list_heavy }...................: 0      0/s
      { scenario:list_typical }.................: 0      0/s
      { scenario:portfolio_heavy }..............: 0      0/s
      { scenario:portfolio_typical }............: 0      0/s
      { scenario:search }.......................: 0      0/s
      { scenario:submit_heavy }.................: 0      0/s
      { scenario:submit_typical }...............: 0      0/s
    iteration_duration..........................: count=5557 med=7.79ms  p(95)=35.32ms p(99)=53.5ms  max=112.56ms
    iterations..................................: 5557   58.480207/s
    vus.........................................: 0      min=0         max=0 
    vus_max.....................................: 50     min=50        max=50
```
