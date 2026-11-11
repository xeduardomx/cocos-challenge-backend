# Verificación final

> Corrida del 2026-11-09 sobre un clon limpio del commit `3bca905`, en la misma computadora de la
> [medición de tiempo de respuesta](06-tiempo-de-respuesta.md). Después de esa corrida cambiaron documentos, la forma de apuntar a Neon, que se probó aparte (`docker compose up`, la prueba de humo y Postman en la copia local, y `run-on-neon.sh` con lecturas), y el paquete de cada clase, con todos los pasos repetidos sobre otro clon limpio ([abajo](#después-de-reordenar-los-paquetes-2026-11-10)). Por último se agregó el control de posibles duplicados, otra vez con todos los pasos sobre un clon limpio ([abajo](#con-el-control-de-posibles-duplicados-2026-11-10)), y se ajustó el tablero de Grafana, sin cambios en el código de la API ([abajo](#con-el-tablero-ajustado-2026-11-11)). Antes de entregar se repitieron los pasos desde un clon de `main` ([al final](#desde-main-antes-de-entregar-2026-11-11)). GitHub
> Actions está bloqueado en esta cuenta (RFC, sección 11.2): estos son los pasos de `.github/workflows/ci.yml`, corridos en
> local, más los ejemplos del README y la medición con volumen. Postgres usó el puerto 5434, porque el 5433 estaba
> ocupado en esa máquina.

| Paso | Comando | Resultado |
|---|---|---|
| Formato, análisis estático, tests y cobertura | `./mvnw -B verify`, con Temurin 25 en Docker | 372 tests, sin fallas. Cobertura: 99,1 % de las líneas y 95,4 % de las ramas (el piso es 85 % y 80 %) |
| Levantar todo | `docker compose up -d --build --wait` | código 0 |
| Prueba de humo | `./scripts/smoke-test.sh` | pasó |
| Ejemplos del README | los `curl` de "Probarla" | las respuestas que muestra el README |
| Colección de Postman, sobre un `up` nuevo | `./scripts/run-postman.sh` | 9 pedidos y 19 aserciones, sin fallas |
| Tiempo de respuesta con 2 millones de órdenes | `./scripts/run-load-test.sh` | código 0, con todos los umbrales cumplidos. El peor percentil 95 es el envío del usuario pesado: 57,13 ms |

```text
== ./mvnw -B verify
Tests run: 372, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS

== docker compose up -d --build --wait
exit: 0

== ./scripts/smoke-test.sh
Smoke test passed: Prometheus scrapes the API, Grafana has the dashboard and a healthy data source, and user 1 has cash 627500 and total value 889756.

== los ejemplos del README
{"totalValue":889756,"cash":{"balance":753000,"reserved":125500,"available":627500}}
{"ticker":"PAMP","name":"Pampa Holding S.A.","lastPrice":925.85}
{"id":12,"ticker":"PAMP","quantity":10,"price":925.85,"amount":9258.5,"status":"FILLED"}
el mismo pedido otra vez: 200
{"id":7,"ticker":"BMA","status":"NEW"}
{"id":5,"ticker":"LOMA","status":"NEW"}
{"id":7,"status":"CANCELLED"}

== ./scripts/run-postman.sh
requests   9, failed 0
assertions 19, failed 0

== ./scripts/run-load-test.sh
exit: 0
```

**La memoria de la medición con volumen.** La base vive en memoria, así que esta corrida es la que más usa. Medido con
`docker stats` cada pocos segundos, el pico de los contenedores juntos fue de 3,28 GiB: Postgres 1,73, Grafana 0,84, la API
0,65, y k6 y Prometheus 0,03 cada uno. En la corrida anterior fue de 3,98 GiB, porque la API llegó a 1,38. Por eso el README
pide unos 4 GB libres.

## Después de reordenar los paquetes (2026-11-10)

Las clases de cada función se agruparon en un paquete por capa, sin cambiar lógica
([ADR 0001, "Revisión"](../adr/0001-capas-por-funcion.md#revisión-fase-8-2026-11-10)).

**Antes y después de mover las clases,** en el directorio de trabajo:

| Qué | Antes | Después |
|---|---|---|
| Tests | 372, sin fallas | 372, sin fallas, con la misma cantidad en cada una de las 44 clases de test |
| Cobertura, del informe de JaCoCo | 865 de 873 líneas y 228 de 239 ramas, en 89 clases | lo mismo, clase por clase |
| Código | | 80 archivos movidos (57 clases y 23 tests) y 15 con otros `import`: solo cambian las líneas `package` e `import`. Cada paquete nuevo tiene su `package-info.java` |

**Todos los pasos de arriba, otra vez,** sobre un clon limpio del commit `f56af3d`:

| Paso | Comando | Resultado |
|---|---|---|
| Formato, análisis estático, tests y cobertura | `./mvnw -B verify`, con Temurin 25 en Docker | 372 tests, sin fallas. Cobertura: 99,1 % de las líneas y 95,4 % de las ramas |
| Levantar todo | `docker compose up -d --build --wait` | código 0 |
| Prueba de humo | `./scripts/smoke-test.sh` | pasó |
| Ejemplos del README | los `curl` de "Probarla" | las respuestas que muestra el README |
| Colección de Postman, sobre un `up` nuevo | `./scripts/run-postman.sh` | 9 pedidos y 19 aserciones, sin fallas |
| Tiempo de respuesta con 2 millones de órdenes | `./scripts/run-load-test.sh` | código 0, con todos los umbrales cumplidos. El peor percentil 95 sigue siendo el envío del usuario pesado: 56,12 ms (antes, 57,13) |

```text
== ./mvnw -B verify
Tests run: 372, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS

== docker compose up -d --build --wait
exit: 0

== ./scripts/smoke-test.sh
Smoke test passed: Prometheus scrapes the API, Grafana has the dashboard and a healthy data source, and user 1 has cash 627500 and total value 889756.

== los ejemplos del README
{"totalValue":889756,"cash":{"balance":753000,"reserved":125500,"available":627500}}
{"ticker":"PAMP","name":"Pampa Holding S.A.","lastPrice":925.85}
{"id":12,"ticker":"PAMP","quantity":10,"price":925.85,"amount":9258.5,"status":"FILLED"}
el mismo pedido otra vez: 200
{"id":7,"ticker":"BMA","status":"NEW"}
{"id":5,"ticker":"LOMA","status":"NEW"}
{"id":7,"status":"CANCELLED"}

== ./scripts/run-postman.sh
requests   9, failed 0
assertions 19, failed 0

== ./scripts/run-load-test.sh
exit: 0
```

**Además, a mano,** con el usuario 1 y sobre los datos originales, antes del commit:

| Pedido | Respuesta |
|---|---|
| Portfolio | $889.756 en total y $627.500 disponibles |
| Búsqueda de "pamp" | PAMP, a $925,85 |
| Compra a mercado de 2 PAMP | 201, `FILLED` a $925,85 |
| El mismo pedido otra vez | 200, con la misma orden |
| Venta límite de 15 PAMP a $1.000 | 201, `NEW`; el portfolio muestra `reservedQuantity` 15 |
| Venta a mercado de 30 PAMP, con 27 libres | `REJECTED` por `INSUFFICIENT_SHARES` |
| Cancelar la venta límite | 200, `CANCELLED`; `reservedQuantity` vuelve a 0 |
| Cancelarla otra vez | 409, con `currentStatus` `CANCELLED` |
| Portfolio del usuario 999 | 404 |
| Orden límite sin precio y sin identificador | 400, con los dos errores |

El log de cada orden sale con el nombre nuevo de la clase, `com.cocos.challenge.orders.observability.OrderEventLogger`,
y los contadores `orders_recorded_total`, `orders_replayed_total` y `orders_cancelled_total` contaron cada una.

## La colección de Postman, con lo reservado (2026-11-10)

La colección sumó cuatro pasos: una venta límite que queda `NEW`, su cancelación y dos portfolios, uno con las dos
órdenes pendientes y otro después de cancelarlas. Comparan contra lo reservado en el paso 1: la compra límite suma
$100.000 a `cash.reserved` y no reserva acciones, y la venta suma 5 a `reservedQuantity` de PAMP
([RFC, sección 11.5](../03-rfc.md#115-la-colección-de-postman)). Sobre un `up` nuevo:

```text
== ./scripts/run-postman.sh
requests  13, failed 0
assertions 32, failed 0
```

## Con el control de posibles duplicados (2026-11-10)

La API sumó una segunda red contra órdenes duplicadas: una orden igual a otra aceptada del mismo usuario hace menos de
5 segundos, con otro identificador, se frena con 409
([RFC, sección 5.5](../03-rfc.md#55-la-misma-orden-con-otro-identificador)). Cambió el camino del envío, así que se
repitieron todos los pasos.

**Todos los pasos de arriba, otra vez,** sobre un clon limpio del commit `3fd66f3`:

| Paso | Comando | Resultado |
|---|---|---|
| Formato, análisis estático, tests y cobertura | `./mvnw -B verify`, con Temurin 25 en Docker | 397 tests, sin fallas: 25 más que antes. Cobertura: 99,3 % de las líneas y 95,6 % de las ramas |
| Levantar todo | `docker compose up -d --build --wait` | código 0 |
| Prueba de humo | `./scripts/smoke-test.sh` | pasó |
| Ejemplos del README | los `curl` de "Probarla" | las respuestas que muestra el README, con el ejemplo nuevo: la misma orden con otro identificador da 409 |
| Colección de Postman, sobre un `up` nuevo | `./scripts/run-postman.sh` | 13 pedidos y 32 aserciones, sin fallas. La colección no cambió |
| Tiempo de respuesta con 2 millones de órdenes | `./scripts/run-load-test.sh` | código 0, con todos los umbrales cumplidos. El peor percentil 95 sigue siendo el envío del usuario pesado: 61,4 ms |

```text
== ./mvnw -B verify
Tests run: 397, Failures: 0, Errors: 0, Skipped: 0
All coverage checks have been met.
BUILD SUCCESS

== docker compose up -d --build --wait
exit: 0

== ./scripts/smoke-test.sh
Smoke test passed: Prometheus scrapes the API, Grafana has the dashboard and a healthy data source, and user 1 has cash 627500 and total value 889756.

== los ejemplos del README
{"totalValue":889756,"cash":{"balance":753000,"reserved":125500,"available":627500}}
{"ticker":"PAMP","name":"Pampa Holding S.A.","lastPrice":925.85}
{"id":12,"ticker":"PAMP","quantity":10,"price":925.85,"amount":9258.5,"status":"FILLED"}
el mismo pedido otra vez: 200
{"status":"FILLED","detail":null}
{"status":409,"detail":"Acaba de entrar una orden igual, la 13. Si querés otra igual, esperá unos segundos y volvé a enviarla."}
{"id":7,"ticker":"BMA","status":"NEW"}
{"id":5,"ticker":"LOMA","status":"NEW"}
{"id":7,"status":"CANCELLED"}

== ./scripts/run-postman.sh
requests  13, failed 0
assertions 32, failed 0

== ./scripts/run-load-test.sh
exit: 0
```

**Lo que encontró la verificación, antes de este clon.** Todo corregido, y con su test donde hay código:

| Quién la encontró | Qué pasaba | Qué se hizo |
|---|---|---|
| La suite, al correrla entera | el test del camino sin turno recibía un 409 en vez de devolver la orden original | la consulta deja afuera la orden con el mismo identificador: esa es un reintento |
| La medición con volumen | el percentil 95 del envío del usuario pesado subió a 72,9 ms: la consulta leía sus 10.000 órdenes | "ahora" pasó de `clock_timestamp()` a `statement_timestamp()`, y el índice recorre solo la ventana ([`05-duplicado-resultados.md`](05-duplicado-resultados.md)) |
| La revisión del código | el campo `existingOrderId` no estaba en el esquema de errores que publica Swagger; "aceptada" estaba definido en dos lugares; con la ventana en 0 la consulta se hacía igual | el esquema lo documenta, hay una sola definición (`OrderStatus.accepted()`) y con 0 no se consulta |
| Una corrida de la medición sobre el commit `0dd94db` | dio tiempos tres veces más bajos en todos los escenarios: 18,99 ms el envío del usuario pesado | no era el código: otro proceso tenía ocupado un núcleo. Quedó comprobado y escrito en [`06-tiempo-de-respuesta.md`](06-tiempo-de-respuesta.md#la-máquina-en-reposo-mide-de-más), y esa corrida no se usa como resultado |

**Cada test nuevo se vio fallar.** Los primeros, antes de escribir el código. Y al final, sobre el código terminado, se
rompió a propósito de once formas, una por vez:

| Rotura | Test que falló |
|---|---|
| Sin el control | los que esperan el 409: la misma orden enseguida, la límite pendiente, la orden por monto, la venta, la venta límite y la orden a mitad de la ventana |
| La ventana en microsegundos, por un error de unidad | los mismos |
| Solo cuentan las `FILLED` | el de la orden límite pendiente, el de la venta límite y el de la definición de "aceptada" |
| Sin filtrar por usuario | el de otro usuario con la misma orden |
| Sin comparar la huella | el de otra cantidad |
| Sin la condición de la hora | el de la ventana en 0 y el que cuenta las filas leídas |
| La más vieja primero | el que pide la más nueva |
| Sin excluir el mismo identificador | el del identificador propio y el del camino sin turno |
| El control antes de tomar el turno | el de dos iguales al mismo tiempo |
| `clock_timestamp()` en lugar de `statement_timestamp()` | el que cuenta las filas leídas: 11 en vez de 0 |
| Con la ventana en 0, la consulta se hace igual | el del control apagado, que espía el repositorio |

**Además, a mano,** con el compose:

| Prueba | Resultado |
|---|---|
| Dos compras iguales seguidas, con identificadores distintos | 201 y 409, con `existingOrderId` |
| La que se frenó, reenviada a los 6 segundos con el mismo identificador | 201, `FILLED`: el 409 no gastó el identificador |
| El 409 en los logs y en las métricas | una línea `WARN` con su `problemType`, y `http_server_requests_seconds_count` con `status="409"` para el envío |
| Con `API_DUPLICATE_ORDER_WINDOW=0s` | las dos se aceptan: el control queda apagado |
| Con `API_DUPLICATE_ORDER_WINDOW=-1s` | la API no arranca: "must be longer than or equal to 0" |
| El recorrido de dos compras de $600.000 a la vez | una `FILLED` y una `REJECTED`, como antes |

## Con el tablero ajustado (2026-11-11)

El tablero de Grafana pasó de cinco a seis paneles. Los errores se separaron en errores del servidor (5xx) y del cliente
(4xx), los cuatro paneles que cuentan pasaron a leer el contador desde que arrancó la API, y cada panel sumó su
explicación ([RFC, sección 8.3](../03-rfc.md#83-cómo-se-ven-prometheus-y-grafana-en-el-mismo-docker-compose)). No cambió
el código de la API ni sus tests: cambió `observability/grafana/dashboards/cocos-api.json`. Se probó en un segundo
`docker compose`, con otros puertos y el mismo archivo, para poder reiniciar la API y provocar errores.

**Con el archivo final,** sobre un `up` nuevo:

| Paso | Resultado |
|---|---|
| Prueba de humo, `./scripts/smoke-test.sh` | pasó |
| Colección de Postman | 13 pedidos y 32 aserciones, sin fallas |
| El portal, `./scripts/portal.sh build` | arma sin avisos, con los enlaces entre páginas revisados |

**Cada panel, contra lo que se le mandó a la API:**

| Prueba | Qué mostró el tablero |
|---|---|
| Un error de cada tipo, una sola vez: siete del cliente, un 503 y un 500 | siete barras en 1, y los dos números de errores del servidor en 1 y en rojo |
| La API recién arrancada | 0 y 0 en verde, y "Sin errores del cliente" |
| Un solo error del cliente | una barra con su nombre: `404 · GET /users/{userId}/portfolio` |
| La API detenida | "Sin datos: la API no responde", en naranja |
| Durante un minuto, otra transacción toma el turno del usuario 150 ms de cada 400, con 356 envíos | el percentil 95 del envío sube a 156 ms y aparece la línea roja de los 100 ms. La espera del turno marca 151 ms |
| La colección de Postman, corrida apenas levanta todo y sin la prueba de humo, tres veces | las 4 órdenes, el rechazo y el 422, las tres veces |
| Diez minutos de tráfico de ejemplo | cada número es el del contador de la API: 1.900 órdenes en seis combinaciones, 246 rechazos por tres motivos y 471 errores del cliente en siete barras |

Con el panel anterior, un error que pasaba una sola vez no se veía: después de la colección de Postman, su consulta no
devolvía nada para el 422.

**Lo que apareció al probar,** antes de dar por bueno el archivo:

| Qué pasaba | Qué se hizo |
|---|---|
| Los paneles de órdenes y de rechazos quedaban vacíos si la colección de Postman se corría apenas levantaba todo: las tres veces que se probó, con 4 órdenes grabadas. Contaban con `increase()`, que no ve lo que pasa antes de la primera lectura de Prometheus, y esa lectura llegó entre 3,6 y 10,5 segundos después de que la API estuvo lista (cuatro corridas). Ya pasaba con el tablero anterior. Con la prueba de humo antes, no, porque espera esa lectura | los cuatro paneles que cuentan leen el contador desde que arrancó la API |
| Con un solo tipo de error, su barra salía sin nombre. También pasaba en el panel de órdenes, con una sola combinación | cada barra lleva su nombre |
| El nombre largo de la cancelación se cortaba y tapaba el código | el código va primero: `409 · POST /users/…` |
| Con la API detenida, los errores del servidor mostraban un 0 en verde | el 0 se muestra solo si Prometheus está leyendo la API |
| Un ícono azul de aviso en el panel de latencia, una vez, con muchos pedidos justo después de reiniciar la API | es un aviso informativo de Prometheus, que corrige solo una diferencia mínima. Quedó en el [runbook](../portal/runbook.md#el-panel-de-latencia-muestra-un-ícono-azul-de-información) |

**La captura del portal** ([métricas](../portal/metricas.md)) es de ese segundo compose, con los diez minutos de tráfico
de ejemplo.

## Desde main, antes de entregar (2026-11-11)

Sobre un clon nuevo del repositorio, que trae `main` en `85e62b5` (tag `v1.0.0`), sin nada guardado de corridas
anteriores. Postgres usó el puerto 5434.

| Paso | Resultado |
|---|---|
| `./mvnw -B verify`, con el comando del README para Docker, bajando todas las dependencias | 397 tests, sin fallas, con el piso de cobertura cumplido |
| `docker compose build --no-cache --pull` | la imagen de la API compila desde cero |
| `docker compose up --build` | levanta la API, Postgres, Prometheus y Grafana |
| `./scripts/smoke-test.sh` | pasó |
| `./scripts/run-postman.sh` | 13 pedidos y 32 aserciones, sin fallas |
| El tablero, después de la colección | las 4 órdenes, una de ellas rechazada, y el 422. Los errores del servidor, en 0 |
| `./scripts/run-on-neon.sh` contra una base local ya migrada, sin migrar | la API arranca con las migraciones apagadas, y la prueba de humo pasó |
| Los enlaces del README | los 17 que van a archivos del repositorio apuntan a algo que existe, y los del portal responden |

**Contra la base de Neon,** con el mismo contenido: el autor levantó la API con `./scripts/run-on-neon.sh` y corrió la
prueba de humo, que solo lee. Pasó: el usuario 1 tiene los números originales. Contra Neon solo se hicieron lecturas, y
tardaron unos 800 ms, por la distancia hasta esa base
([RFC, sección 8.4](../03-rfc.md#84-el-objetivo-de-tiempo-de-respuesta-gen-8)).

Después de esta corrida cambiaron solo documentos: la nota del README sobre Neon y lo que se escribió a partir de ella.
