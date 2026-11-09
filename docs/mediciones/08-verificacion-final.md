# Verificación final

> Corrida del 2026-11-09 sobre un clon limpio del commit `3bca905`, en la misma computadora de la
> [medición de tiempo de respuesta](06-tiempo-de-respuesta.md). Después de esa corrida solo cambiaron documentos y la forma de apuntar a Neon, que se probó aparte: `docker compose up`, la prueba de humo y Postman en la copia local, y `run-on-neon.sh` con lecturas. GitHub
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
