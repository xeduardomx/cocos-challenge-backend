# Runbook

Cómo levantar la API, comprobar que funciona y qué hacer cuando algo falla. Los comandos se corren desde la raíz del
repositorio y van contra la copia local: ninguno toca la base de Cocos.

## Levantarla y comprobarla

```bash
docker compose up --build -d
./scripts/smoke-test.sh
```

Levantarla solo necesita Docker; los scripts y los ejemplos de esta página también usan `curl` y `jq`. La prueba de humo espera a la API y verifica que Prometheus la lea, que Grafana tenga su tablero y que el usuario 1 dé
los números originales. Si todo está bien, termina con:

```text
Smoke test passed: Prometheus scrapes the API, Grafana has the dashboard and a healthy data source, and user 1 has cash 627500 and total value 889756.
```

| Qué | Dónde |
|---|---|
| API | <http://localhost:8080> |
| Página para probar la API | <http://localhost:8080/swagger-ui.html> |
| Salud de la API | <http://localhost:8080/actuator/health> |
| Tablero de Grafana, sin usuario ni clave | <http://localhost:3000> |
| Prometheus | <http://localhost:9090> |
| Postgres | `localhost:5433`, con usuario y clave `postgres` |

Para bajar todo: `docker compose down`. La base vive en memoria, así que cada `docker compose up` arranca con los datos
originales.

## Seguir un pedido por sus logs

Cada pedido tiene un id, que vuelve en el encabezado `X-Request-Id` y en los errores, y aparece en todas las líneas de
log de ese pedido. Se puede elegir, con letras, números y guiones, hasta 64 caracteres:

```bash
curl -si -X POST http://localhost:8080/users/1/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: mi-pedido-1' \
  -d '{"clientOrderId":"3f2b8c1e-7a4d-4e9b-9c55-0a1b2c3d4e5f","instrumentId":47,"side":"BUY","type":"MARKET","quantity":1}'

docker compose logs api --no-log-prefix | grep mi-pedido-1 | jq -c '{message, userId, orderId, status}'
```

```json
{"message":"Order processed","userId":"1","orderId":"12","status":"FILLED"}
```

La API escribe una línea por cada orden procesada, cancelación o error. Un portfolio o una búsqueda que salen bien no
escriben ninguna.

## Qué hacer si...

### El puerto ya está ocupado

```text
Bind for 127.0.0.1:5433 failed: port is already allocated
```

Otro programa usa ese puerto. Se mueve con una variable:

```bash
POSTGRES_PORT=5434 docker compose up --build -d
```

Las otras son `API_PORT` (8080), `PROMETHEUS_PORT` (9090) y `GRAFANA_PORT` (3000). Con otro puerto, los scripts lo
reciben en `API_URL`, `PROMETHEUS_URL` y `GRAFANA_URL`.

### Una orden responde 503 `/problems/user-busy`

```json
{"detail":"Tu cuenta está procesando otra orden. Intentá de nuevo.","instance":"/users/2/orders","status":503,"title":"La cuenta está ocupada","type":"/problems/user-busy","requestId":"mi-pedido-3"}
```

Otra operación tiene el turno de ese usuario desde hace más de 2 segundos. La respuesta trae `Retry-After: 1`, y
reintentar con el mismo `clientOrderId` es seguro: no compra dos veces
([RFC 5.3](../03-rfc.md#53-reintentos)).

Si se repite, el panel "Espera del turno" de Grafana muestra cuánto están esperando los envíos, y esta consulta muestra
qué transacciones están abiertas y desde cuándo:

```bash
docker compose exec -T postgres psql -U postgres -X -c "select pid, state, now() - xact_start as en_curso, left(query, 50) as consulta from pg_stat_activity where datname = current_database() and xact_start is not null and pid <> pg_backend_pid();"
```

### Una orden queda `REJECTED`

No es un error: la orden se grabó, rechazada, y responde 201. El campo `rejectionReason` dice por qué:
`INSUFFICIENT_CASH` (no alcanzan los pesos disponibles), `INSUFFICIENT_SHARES` (no alcanzan las acciones) o
`NO_MARKET_PRICE` (el activo no cotiza). El panel "Rechazos por motivo" los cuenta
([RFC 7.4](../03-rfc.md#74-un-rejected-no-es-un-error)).

### Una respuesta da 500 `/problems/internal-error`

La respuesta trae el `requestId`, y con él se encuentra el error en los logs. El stack trace completo está en
`error.stack_trace`:

```bash
docker compose logs api --no-log-prefix | grep EL_REQUEST_ID | jq -c 'select(.log.level == "ERROR") | {message, problemType, httpStatus, error: .error.type}'
```

### La base no responde

Cada pedido espera hasta 30 segundos por una conexión y termina en un 500, con un error de conexión en los logs
(`CannotCreateTransactionException` en un envío). `/actuator/health` tampoco contesta rápido: en la prueba no respondió en
10 segundos. `docker compose ps` muestra si Postgres está corriendo.

Para recuperarla, se baja y se levanta todo:

```bash
docker compose down
docker compose up --build -d
```

**No alcanza con reiniciar solo Postgres.** Sus datos viven en memoria, así que vuelve a cargar `database.sql` sin las
migraciones, que la API aplica solo al arrancar. El portfolio funciona, pero la búsqueda, el envío, el listado y la
cancelación dan 500.

### Grafana no muestra la latencia o los errores

No es una falla. Las órdenes y la espera del turno se ven desde el primer pedido. La latencia y los errores de cada
función aparecen desde el segundo, con al menos 15 segundos entre los dos, porque Prometheus necesita dos lecturas.

### La colección de Postman falla en la segunda corrida

```text
AssertionError  available cash is 627500
                expected 618241.5 to deeply equal 627500
```

La colección espera los datos originales, y la primera corrida ya los cambió. Se baja y se levanta todo.

## Contra la base de Cocos (Neon)

`docker compose` usa siempre la copia local. Para apuntar la API a Neon, con la dirección en `DATABASE_URL` dentro de
`.env` (ver `.env.example`):

```bash
./scripts/run-on-neon.sh
```

Queda solo la API, en <http://localhost:8080>. **Cada orden que se le mande se graba en esa base,** así que la prueba de
humo, la colección de Postman y la prueba de carga no son para ella: van contra la copia local.

Las migraciones ya están aplicadas ([cómo se hizo](../mediciones/08-migracion-neon.md)). En una base nueva, el mismo script
con `--migrate` las aplica antes de arrancar, después de pedir confirmación. Correrlo otra vez no hace nada: Flyway mira
su historial y ve que no queda ninguna pendiente.

## Otras tareas

| Tarea | Comando | Qué necesita |
|---|---|---|
| Correr todos los tests | `cd api && ./mvnw verify` | Java 25 y Docker |
| Recorrer la API con Postman | `./scripts/run-postman.sh` | todo levantado, con los datos originales, y `curl` y `jq` |
| Medir el tiempo de respuesta | `./scripts/run-load-test.sh` | Docker con unos 4 GB libres, porque la base vive en memoria; levanta y baja todo solo |
| Ver este portal en local | `./scripts/portal.sh` | Docker; queda en <http://localhost:8000> |
| Apuntar la API a Neon | `./scripts/run-on-neon.sh` | Docker y `DATABASE_URL` en `.env` |
