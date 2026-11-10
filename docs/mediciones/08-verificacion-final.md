# Verificación final

> Corrida del 2026-11-09 sobre un clon limpio del commit `3bca905`, en la misma computadora de la
> [medición de tiempo de respuesta](06-tiempo-de-respuesta.md). Después de esa corrida cambiaron documentos, la forma de apuntar a Neon, que se probó aparte (`docker compose up`, la prueba de humo y Postman en la copia local, y `run-on-neon.sh` con lecturas), y el paquete de cada clase, con todos los pasos repetidos sobre otro clon limpio ([abajo](#después-de-reordenar-los-paquetes-2026-11-10)). GitHub
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
