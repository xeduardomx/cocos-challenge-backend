# Cocos Capital: challenge backend

API en Java 25 y Spring Boot 4.1, sobre Postgres 18. Hace lo que pide el enunciado (portfolio, búsqueda de activos y
envío de órdenes) y agrega la cancelación y el listado de órdenes: sin cancelación, la plata reservada por una orden
límite quedaría bloqueada para siempre.

**El camino está en el [portal](https://xeduardomx.github.io/cocos-challenge-backend/):** el análisis del enunciado y de
los datos, cada decisión con su porqué, el diseño, los diagramas y las mediciones. En dos minutos:
[las decisiones clave en 12 preguntas](https://xeduardomx.github.io/cocos-challenge-backend/#las-decisiones-clave-en-12-preguntas).

## Levantarla

Alcanza con Docker y `docker compose`. Hay dos comandos: los dos levantan la API, Prometheus y Grafana, y cambia la base.

| Para qué | Comando | Base |
|---|---|---|
| Usar la base que mandó Cocos | `./scripts/run-on-neon.sh` | Neon. Antes, una sola vez: `cp .env.example .env`, y pegar su dirección en `DATABASE_URL` |
| Hacer pruebas con datos descartables | `docker compose up --build` | una copia local, que cada vez arranca con los datos originales |

La primera vez compila la API y tarda unos minutos. Después:

| Qué | Dónde |
|---|---|
| API | http://localhost:8080 |
| Página para probar la API (Swagger) | http://localhost:8080/swagger-ui.html |
| Especificación OpenAPI | http://localhost:8080/v3/api-docs |
| Tablero de Grafana, sin usuario ni clave | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Postgres, solo con la copia local, con usuario y clave `postgres` | `localhost:5433` |

Para bajar todo: `docker compose down`.

- **Neon:** ya tiene aplicadas las migraciones, y sus datos no cambiaron
  ([cómo se hizo](docs/mediciones/08-migracion-neon.md)). Cada orden que se le mande se graba ahí, así que la colección de
  Postman y la prueba de carga van contra la copia local.
- **La copia local:** se arma con `docs/00-fuentes/database.sql` y las migraciones de la API, y vive en memoria. Cada
  `docker compose up` arranca con los datos originales, y nunca usa la base de Neon.
- **Los puertos** se abren solo en tu máquina (`127.0.0.1`). Si alguno está ocupado, se mueve con una variable:
  `POSTGRES_PORT=5434 API_PORT=8081 docker compose up --build`. También `PROMETHEUS_PORT` y `GRAFANA_PORT`. Con otro
  puerto, los scripts lo reciben en `API_URL`, `PROMETHEUS_URL` y `GRAFANA_URL`.
- **Si algo falla,** el [runbook](docs/portal/runbook.md) dice qué mirar y qué hacer.

## Probarla

Los ejemplos usan `curl` y `jq`, sobre los datos originales, y la orden se graba en la base que estés usando. El usuario 1
es el que tiene movimientos.

**El portfolio.** Tiene $753.000, pero $125.500 están reservados por dos compras pendientes: puede operar con $627.500.

```bash
curl -s localhost:8080/users/1/portfolio | jq '{totalValue, cash}'
```

```json
{
  "totalValue": 889756,
  "cash": {
    "balance": 753000,
    "reserved": 125500,
    "available": 627500
  }
}
```

**La búsqueda,** por ticker o por nombre, sin importar mayúsculas ni acentos:

```bash
curl -s 'localhost:8080/instruments?query=pamp' | jq -c '.instruments[] | {ticker, name, lastPrice}'
```

```json
{"ticker":"PAMP","name":"Pampa Holding S.A.","lastPrice":925.85}
```

**Una orden.** Cada una lleva un identificador que genera el cliente (`clientOrderId`, un UUID):

```bash
curl -s -X POST localhost:8080/users/1/orders -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"3f2b8c1e-7a4d-4e9b-9c55-0a1b2c3d4e5f","instrumentId":47,"side":"BUY","type":"MARKET","quantity":10}' \
  | jq -c '{id, ticker, quantity, price, amount, status}'
```

```json
{"id":12,"ticker":"PAMP","quantity":10,"price":925.85,"amount":9258.50,"status":"FILLED"}
```

Si mandás exactamente lo mismo otra vez, responde 200 con la misma orden y no compra de nuevo: así un reintento no
gasta dos veces.

**Las órdenes pendientes y cancelar una:**

```bash
curl -s 'localhost:8080/users/1/orders?status=NEW' | jq -c '.orders[] | {id, ticker, status}'
curl -s -X POST localhost:8080/users/1/orders/7/cancel | jq -c '{id, status}'
```

```json
{"id":7,"ticker":"BMA","status":"NEW"}
{"id":5,"ticker":"LOMA","status":"NEW"}
{"id":7,"status":"CANCELLED"}
```

Todas las funciones, con sus pedidos, respuestas y errores, están en la
[sección 6 del RFC](docs/03-rfc.md#6-contratos-de-la-api) y en la página de Swagger.

| Función | Pedido |
|---|---|
| Portfolio | `GET /users/{userId}/portfolio` |
| Búsqueda de activos | `GET /instruments?query={texto}` |
| Envío de una orden | `POST /users/{userId}/orders` |
| Listado de órdenes | `GET /users/{userId}/orders?status={estado}&limit={n}&cursor={marca}` |
| Cancelación de una orden | `POST /users/{userId}/orders/{orderId}/cancel` |

El usuario va en la dirección porque el enunciado pide no autenticar. Una orden rechazada por falta de plata o de
acciones no es un error: se graba como `REJECTED`, responde 201 y trae el motivo.

## Verificarla

| Qué | Comando | Qué necesita |
|---|---|---|
| Prueba de humo: Prometheus lee la API, Grafana tiene el tablero y el usuario 1 da los números originales | `./scripts/smoke-test.sh` | todo levantado, `curl` y `jq` |
| Colección de Postman: recorre la API en nueve pasos, con sus tests | `./scripts/run-postman.sh` | todo levantado, recién arrancado |
| Todos los tests, formato, análisis estático y cobertura | `cd api && ./mvnw verify` | Java 25 y Docker |
| Tiempo de respuesta con 2 millones de órdenes | `./scripts/run-load-test.sh` | Docker con unos 4 GB libres, porque la base vive en memoria; levanta y baja todo solo |

La colección espera los datos originales, así que una segunda corrida sin bajar y volver a levantar falla. Para usarla
desde Postman, importá los dos archivos de `postman/`.

Sin Java 25, los tests también corren con Docker, desde la raíz del repositorio:

```bash
docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD:$PWD" -w "$PWD/api" \
  -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  maven:3.9-eclipse-temurin-25 ./mvnw -B verify
```

En Grafana, las órdenes y la espera del turno se ven desde el primer pedido. La latencia y los errores de cada función
aparecen desde el segundo, con al menos 15 segundos entre los dos: Prometheus necesita dos lecturas.

El resultado de la medición con volumen está en
[`docs/mediciones/06-tiempo-de-respuesta.md`](docs/mediciones/06-tiempo-de-respuesta.md).

**Sobre el CI.** `.github/workflows/ci.yml` corre el build, los tests, la prueba de humo y la colección de Postman en cada
push, pero GitHub Actions está bloqueado en esta cuenta y sus jobs no llegan a arrancar: por eso los commits muestran una
cruz roja. Los mismos pasos se corrieron en local, sobre un clon limpio:
[`docs/mediciones/08-verificacion-final.md`](docs/mediciones/08-verificacion-final.md).

## El portal en tu máquina

El [portal](https://xeduardomx.github.io/cocos-challenge-backend/) se genera desde `docs/`, y también se puede levantar
en local con Docker. Queda en http://localhost:8000, y el puerto se mueve con `PORTAL_PORT`:

```bash
./scripts/portal.sh
```

## Dónde está cada cosa

| Documento | Qué contesta |
|---|---|
| [Enunciado](docs/00-fuentes/backend-challenge.md) y [datos](docs/00-fuentes/database.sql) | qué pidió Cocos |
| [Análisis](docs/01-analisis.md) | qué se encontró en el enunciado y en los datos reales |
| [Registro de decisiones](docs/01-decisiones.md) | cada decisión con su porqué, y todo lo agregado respecto del enunciado |
| [PRD](docs/02-prd.md) | qué tiene que hacer la API, con sus casos de aceptación |
| [RFC](docs/03-rfc.md) y [ADR](docs/adr/README.md) | cómo se construye, y las opciones evaluadas en las decisiones grandes |
| [Plan](docs/04-plan.md) | en qué orden se implementó |
| [Mediciones](docs/mediciones/README.md) | lo que el RFC afirma con un número, con su script y su salida |
| [Runbook](docs/portal/runbook.md) y [métricas](docs/portal/metricas.md) | cómo operarla y qué se mide |

```text
api/            la API: código, tests, migraciones y Dockerfile
docs/           el análisis, las decisiones, el diseño y las mediciones
observability/  la configuración de Prometheus y el tablero de Grafana
perf/           la prueba de carga con k6 y sus datos de volumen
postman/        la colección de Postman
scripts/        prueba de humo, Postman, prueba de carga y portal
```

El código de `api/` tiene un paquete por función (`account`, `instruments`, `portfolio` y `orders`) y, adentro de cada
uno, un paquete por capa: `web`, `service`, `domain`, `repository`, `config`, `error` y `observability`
([RFC, sección 2](docs/03-rfc.md#2-arquitectura)).
