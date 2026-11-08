# Cocos Capital: challenge backend

API en Java (Spring Boot) con el portfolio de un usuario, la búsqueda de activos y el envío de órdenes.
Este README es mínimo por ahora; se completa en la fase 8.

## Requisitos

- Docker con `docker compose`, para levantar la API y su base.
- Java 25, solo para correr los tests (`curl` y `jq` para el script de humo).

## Levantarla

```bash
docker compose up --build
```

- API: http://localhost:8080
- Especificación OpenAPI: http://localhost:8080/v3/api-docs
- Página de la API: http://localhost:8080/swagger-ui.html

Si el puerto 5433 de tu máquina está ocupado, el Postgres local se mueve con `POSTGRES_PORT=5434 docker compose up --build`.

La base se arma con `docs/00-fuentes/database.sql` y las migraciones de la API, y vive en memoria (`tmpfs`): cada
`docker compose up` arranca con los datos originales.

## Prueba de humo

Con la API levantada, pide el portfolio del usuario 1 y verifica sus números:

```bash
./scripts/smoke-test.sh
```

## Tests

```bash
cd api && ./mvnw verify
```
