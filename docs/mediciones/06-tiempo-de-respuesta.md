# Tiempo de respuesta con volumen

> Corrida del 2026-11-08 en una laptop, con la API, Postgres 18.6, Prometheus, Grafana y k6 2.3.0 en Docker en la misma
> máquina, y los datos en memoria: sirven como orden de magnitud. Es la salida de
> [`scripts/run-load-test.sh`](../../scripts/run-load-test.sh), que carga
> [`perf/seed-volume.sql`](../../perf/seed-volume.sql) y corre [`perf/load-test.js`](../../perf/load-test.js) con k6.

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
| `submit_typical`, `submit_heavy` | Compra a mercado 1 acción de METR ($229,50). Cada envío lleva su propio `clientOrderId` |
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
escenario exige que haya pedidos (`http_reqs: count>0`), que todas las respuestas sean las esperadas
(`checks: rate==1`) y que k6 haya podido mandar todos los pedidos previstos (`dropped_iterations: count==0`).

## La máquina

Una laptop, con todo en la misma máquina: k6, la API, Postgres, Prometheus y Grafana, en Docker.

| | |
|---|---|
| CPU | Intel Core i7-12700F, 12 núcleos (8 de rendimiento y 4 de eficiencia), 20 hilos |
| Memoria | 31 GB |
| Docker | 28.1.1, con Compose 2.35.1 |

La API y Postgres corren con los valores por defecto del `docker-compose.yml`, sin límites de CPU ni memoria, y los
datos de Postgres viven en memoria (`tmpfs`).

## Los datos y la carga

Los datos son los del RFC 3.5, armados con [`perf/seed-volume.sql`](../../perf/seed-volume.sql) sobre el esquema ya
migrado: 2.010.011 órdenes, 342 MB de tabla.

| Usuario | Id | Órdenes | Ejecutadas y pendientes (las que lee la cuenta) |
|---|---|---|---|
| Típicos (10.000) | 5 a 10004 | 100 cada uno | 92 |
| Pesado | 10005 | 10.000 | 8.597 |
| Extremo | 10006 | 1.000.000 | 850.237 |

La semilla sale de la parte de datos de [`03-indices.sql`](03-indices.sql), con `setseed(0.2026)`: la misma secuencia
al azar, y por eso la misma cantidad de órdenes y de pendientes del RFC 3.5. La carga verifica los totales al final y,
si no dan, la corrida se corta. Antes de cargar borra los dos índices de la tabla, al final los vuelve a crear con los
nombres de las migraciones V4 y V6, y termina con `vacuum analyze orders`.

**El depósito inicial es de 100.000.000, no el 1.000.000 del RFC 3.5.** Las órdenes de la semilla son al azar y no miran el
saldo, y con un millón no les alcanza: el 38 % de los típicos (3.825 de 10.000) y el pesado (−$57.927.782 disponibles)
no pueden comprar ni una acción de METR. Una compra rechazada no prueba el camino completo.
Medido con ese depósito, en una corrida corta (15 segundos más el calentamiento): de las compras a mercado, el 37 % de
las del típico y todas las del pesado quedaron `REJECTED`, y las órdenes `LIMIT` del pesado también, así que no había
nada para cancelar. Con el depósito nuevo, todas se ejecutan: la tabla de abajo. Cambia solo ese número: la secuencia al
azar es la misma, y las demás filas no cambian.

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

k6 terminó con código 0, con los nueve umbrales cumplidos. En cada escenario, pedidos, y tiempos en milisegundos:

| Escenario | Pedidos | p50 (ms) | p95 (ms) | p99 (ms) | Máximo (ms) |
|---|---|---|---|---|---|
| `search` | 601 | 3,08 | **4,59** | 7,26 | 28,8 |
| `portfolio_typical` | 601 | 5,65 | **6,82** | 8,05 | 8,63 |
| `portfolio_heavy` | 600 | 26,19 | **34,23** | 37,75 | 41,05 |
| `submit_typical` | 601 | 6,4 | **8,42** | 10,37 | 33,48 |
| `submit_heavy` | 601 | 32,03 | **59,91** | 65,44 | 71,15 |
| `list_typical` | 601 | 2,97 | **4,48** | 5,24 | 30,95 |
| `list_heavy` | 600 | 4,37 | **8,21** | 9,25 | 11,22 |
| `cancel_typical` (solo el pedido de cancelar) | 601 | 1,72 | **3,12** | 3,9 | 6,49 |
| `cancel_heavy` (solo el pedido de cancelar) | 601 | 1,6 | **3,2** | 3,91 | 5,58 |

El envío del usuario pesado es el más lento, con 59,91 ms de percentil 95: calcula la cuenta leyendo sus 8.597 órdenes
ejecutadas y pendientes (más las que le va sumando la corrida), y además tiene 20 envíos por segundo a la vez sobre la
misma cuenta (compras y órdenes `LIMIT` de la cancelación), que se atienden de a uno por el turno del usuario (RFC 5.2).
El portfolio del pesado lee las mismas órdenes, sin turno: 34,23 ms. En la base, esa consulta tarda 1,2 ms (RFC 3.5); el
resto es traer las filas, calcular en Java, armar el JSON y la red.

Otra corrida igual, hecha antes, dio diferencias de menos de 1 ms en el percentil 95 de cada escenario.

## Línea de base

Sobre los datos originales, sin la semilla, con el mismo `perf/load-test.js` y la misma carga. El usuario 1 (el único con
plata) hace de usuario típico y de pesado. k6 terminó con código 0.

| Escenario | Pedidos | p50 (ms) | p95 (ms) | p99 (ms) | Máximo (ms) |
|---|---|---|---|---|---|
| `search` | 601 | 3,37 | **4,93** | 6,38 | 30,39 |
| `portfolio_typical` | 600 | 6,72 | **9,73** | 11,64 | 15,98 |
| `portfolio_heavy` | 601 | 6,86 | **9,72** | 12,12 | 28,96 |
| `submit_typical` | 601 | 17,21 | **33,52** | 39,02 | 45,37 |
| `submit_heavy` | 600 | 15,62 | **34,36** | 40,49 | 45,49 |
| `list_typical` | 600 | 3,78 | **6,41** | 7,56 | 27,66 |
| `list_heavy` | 601 | 3,8 | **6,56** | 7,69 | 14,35 |
| `cancel_typical` (solo el pedido de cancelar) | 601 | 2,28 | **3,57** | 4,34 | 11,09 |
| `cancel_heavy` (solo el pedido de cancelar) | 601 | 2,26 | **3,5** | 4,27 | 5,69 |

El envío tarda más que el de los típicos con volumen (unos 34 ms de percentil 95 contra 8,42 ms) porque acá los 40
envíos por segundo van todos a la misma cuenta y se turnan: la cuenta empieza con pocas órdenes, pero cada envío espera
a los otros.

## Cómo terminaron las órdenes

Órdenes que creó la corrida con volumen, incluido el calentamiento (`orders` con `id` mayor a la última de la semilla):

```text
== Orders created by this run, by kind of user, type and final status (warm-up included)
 user_kind |  type  |  status   | orders 
-----------+--------+-----------+--------
 heavy     | LIMIT  | CANCELLED |    751
 heavy     | MARKET | FILLED    |    751
 typical   | LIMIT  | CANCELLED |    751
 typical   | MARKET | FILLED    |    751
(4 rows)
```

Ninguna compra quedó rechazada: las 751 compras de los típicos y las 751 del pesado se ejecutaron, y las 1.502 órdenes
`LIMIT` se cancelaron. En la línea de base, lo mismo: 1.503 compras `FILLED` y 1.504 órdenes `LIMIT` canceladas.

## El usuario extremo

Fuera del objetivo, como límite documentado (A-4): su portfolio lee 850.237 órdenes ejecutadas y pendientes. Cinco
pedidos sueltos de su portfolio, en esta corrida y con la JVM ya caliente, en segundos: **0,746, 0,831, 0,732, 0,685 y
0,710.** Son unas siete u ocho veces el objetivo de 100 ms. Según `docker stats` (una muestra cada 10 segundos), la API
llega a usar entre 1,3 y 1,8 GB de memoria mientras los atiende, porque trae todas esas órdenes a Java para calcular la
cuenta (RFC 4.1). La salida prevista es la foto a una fecha (A-4).

## Conclusión

**El objetivo del RFC 8.4 se cumple:** con 2.010.011 órdenes en la tabla, el percentil 95 de las cinco funciones queda
por debajo de 100 ms, para usuarios típicos y para el pesado de 10.000 órdenes. El peor es el envío del pesado, 59,91 ms.
La memoria no fue un problema: en todas las corridas quedaron más de 10 GB disponibles en la máquina.

Los límites de esta medición: es una carga moderada de 110 pedidos por segundo durante un minuto, sobre una laptop, con
la base en memoria y k6 compartiendo la máquina con la API; y no dice cuántos pedidos aguanta la API, ni cómo se comporta
por encima de esa carga. El usuario extremo no entra en el objetivo y queda documentado arriba.

## Salida de la corrida con volumen

Lo que imprimió `scripts/run-load-test.sh`, sin las líneas de `docker compose`.

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
    ✓ 'p(95)<100' p(95)=3.2ms

    http_req_duration{scenario:cancel_typical,step:cancel}
    ✓ 'p(95)<100' p(95)=3.12ms

    http_req_duration{scenario:list_heavy}
    ✓ 'p(95)<100' p(95)=8.21ms

    http_req_duration{scenario:list_typical}
    ✓ 'p(95)<100' p(95)=4.48ms

    http_req_duration{scenario:portfolio_heavy}
    ✓ 'p(95)<100' p(95)=34.23ms

    http_req_duration{scenario:portfolio_typical}
    ✓ 'p(95)<100' p(95)=6.82ms

    http_req_duration{scenario:search}
    ✓ 'p(95)<100' p(95)=4.59ms

    http_req_duration{scenario:submit_heavy}
    ✓ 'p(95)<100' p(95)=59.91ms

    http_req_duration{scenario:submit_typical}
    ✓ 'p(95)<100' p(95)=8.42ms

    http_reqs{scenario:cancel_heavy,step:cancel}
    ✓ 'count>0' count=601

    http_reqs{scenario:cancel_typical,step:cancel}
    ✓ 'count>0' count=601

    http_reqs{scenario:list_heavy}
    ✓ 'count>0' count=600

    http_reqs{scenario:list_typical}
    ✓ 'count>0' count=601

    http_reqs{scenario:portfolio_heavy}
    ✓ 'count>0' count=600

    http_reqs{scenario:portfolio_typical}
    ✓ 'count>0' count=601

    http_reqs{scenario:search}
    ✓ 'count>0' count=601

    http_reqs{scenario:submit_heavy}
    ✓ 'count>0' count=601

    http_reqs{scenario:submit_typical}
    ✓ 'count>0' count=601


  █ TOTAL RESULTS 

    checks_total.......: 8259    86.877435/s
    checks_succeeded...: 100.00% 8259 out of 8259
    checks_failed......: 0.00%   0 out of 8259

    ✓ status is 200
    ✓ status is 201
    ✓ the limit order stays NEW

    HTTP
    http_req_duration...........................: count=8259 med=5.64ms  p(95)=38.44ms p(99)=57.46ms max=74.01ms 
      { expected_response:true }................: count=8259 med=5.64ms  p(95)=38.44ms p(99)=57.46ms max=74.01ms 
      { scenario:cancel_heavy,step:cancel }.....: count=601  med=1.6ms   p(95)=3.2ms   p(99)=3.91ms  max=5.58ms  
      { scenario:cancel_typical,step:cancel }...: count=601  med=1.72ms  p(95)=3.12ms  p(99)=3.9ms   max=6.49ms  
      { scenario:list_heavy }...................: count=600  med=4.37ms  p(95)=8.21ms  p(99)=9.25ms  max=11.22ms 
      { scenario:list_typical }.................: count=601  med=2.97ms  p(95)=4.48ms  p(99)=5.24ms  max=30.95ms 
      { scenario:portfolio_heavy }..............: count=600  med=26.19ms p(95)=34.23ms p(99)=37.75ms max=41.05ms 
      { scenario:portfolio_typical }............: count=601  med=5.65ms  p(95)=6.82ms  p(99)=8.05ms  max=8.63ms  
      { scenario:search }.......................: count=601  med=3.08ms  p(95)=4.59ms  p(99)=7.26ms  max=28.8ms  
      { scenario:submit_heavy }.................: count=601  med=32.03ms p(95)=59.91ms p(99)=65.44ms max=71.15ms 
      { scenario:submit_typical }...............: count=601  med=6.4ms   p(95)=8.42ms  p(99)=10.37ms max=33.48ms 
    http_req_failed.............................: 0.00%  0 out of 8259
    http_reqs...................................: 8259   86.877435/s
      { scenario:cancel_heavy,step:cancel }.....: 601    6.321993/s
      { scenario:cancel_typical,step:cancel }...: 601    6.321993/s
      { scenario:list_heavy }...................: 600    6.311474/s
      { scenario:list_typical }.................: 601    6.321993/s
      { scenario:portfolio_heavy }..............: 600    6.311474/s
      { scenario:portfolio_typical }............: 601    6.321993/s
      { scenario:search }.......................: 601    6.321993/s
      { scenario:submit_heavy }.................: 601    6.321993/s
      { scenario:submit_typical }...............: 601    6.321993/s

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
    iteration_duration..........................: count=5557 med=7.24ms  p(95)=54.48ms p(99)=93.65ms max=208.68ms
    iterations..................................: 5557   58.454765/s
    vus.........................................: 0      min=0         max=0 
    vus_max.....................................: 50     min=50        max=50

    NETWORK
    data_received...............................: 27 MB  281 kB/s
    data_sent...................................: 1.3 MB 13 kB/s

== Orders created by this run, by kind of user, type and final status (warm-up included)
 user_kind |  type  |  status   | orders 
-----------+--------+-----------+--------
 heavy     | LIMIT  | CANCELLED |    751
 heavy     | MARKET | FILLED    |    751
 typical   | LIMIT  | CANCELLED |    751
 typical   | MARKET | FILLED    |    751
(4 rows)

== Portfolio of the extreme user (5 loose requests), seconds each
0.745982
0.830993
0.731876
0.684613
0.710279
k6 exit code: 0
```

## Resumen de k6 de la línea de base

```text
  █ TOTAL RESULTS 

    checks_total.......: 8269    87.009463/s
    checks_succeeded...: 100.00% 8269 out of 8269
    checks_failed......: 0.00%   0 out of 8269

    ✓ status is 200
    ✓ status is 201
    ✓ the limit order stays NEW

    HTTP
    http_req_duration...........................: count=8269 med=5.13ms  p(95)=27.72ms p(99)=36.22ms max=45.49ms 
      { expected_response:true }................: count=8269 med=5.13ms  p(95)=27.72ms p(99)=36.22ms max=45.49ms 
      { scenario:cancel_heavy,step:cancel }.....: count=601  med=2.26ms  p(95)=3.5ms   p(99)=4.27ms  max=5.69ms  
      { scenario:cancel_typical,step:cancel }...: count=601  med=2.28ms  p(95)=3.57ms  p(99)=4.34ms  max=11.09ms 
      { scenario:list_heavy }...................: count=601  med=3.8ms   p(95)=6.56ms  p(99)=7.69ms  max=14.35ms 
      { scenario:list_typical }.................: count=600  med=3.78ms  p(95)=6.41ms  p(99)=7.56ms  max=27.66ms 
      { scenario:portfolio_heavy }..............: count=601  med=6.86ms  p(95)=9.72ms  p(99)=12.12ms max=28.96ms 
      { scenario:portfolio_typical }............: count=600  med=6.72ms  p(95)=9.73ms  p(99)=11.64ms max=15.98ms 
      { scenario:search }.......................: count=601  med=3.37ms  p(95)=4.93ms  p(99)=6.38ms  max=30.39ms 
      { scenario:submit_heavy }.................: count=600  med=15.62ms p(95)=34.36ms p(99)=40.49ms max=45.49ms 
      { scenario:submit_typical }...............: count=601  med=17.21ms p(95)=33.52ms p(99)=39.02ms max=45.37ms 
    http_req_failed.............................: 0.00%  0 out of 8269
    http_reqs...................................: 8269   87.009463/s
      { scenario:cancel_heavy,step:cancel }.....: 601    6.323943/s
      { scenario:cancel_typical,step:cancel }...: 601    6.323943/s
      { scenario:list_heavy }...................: 601    6.323943/s
      { scenario:list_typical }.................: 600    6.313421/s
      { scenario:portfolio_heavy }..............: 601    6.323943/s
      { scenario:portfolio_typical }............: 600    6.313421/s
      { scenario:search }.......................: 601    6.323943/s
      { scenario:submit_heavy }.................: 600    6.313421/s
      { scenario:submit_typical }...............: 601    6.323943/s

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
    iteration_duration..........................: count=5557 med=7.77ms  p(95)=35.2ms  p(99)=54.27ms max=115.83ms
    iterations..................................: 5557   58.4728/s
    vus.........................................: 0      min=0         max=0 
    vus_max.....................................: 50     min=50        max=50
```
