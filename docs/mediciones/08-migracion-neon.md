# El pase a Neon

> 2026-11-09. Las migraciones V2 a V6 se aplicaron una sola vez a la base que mandó Cocos, con el mismo Flyway y los mismos
> archivos que la copia local (RFC, sección 3.6). No se mandó ninguna orden: cambió la estructura, no los datos.

El enunciado lo permite: *"El candidato tiene la libertad de modificar, agregar o ajustar tablas según lo considere
necesario [...]. Es importante justificar adecuadamente cualquier cambio en la documentación."* Cada cambio tiene su porqué
en la sección 3 del RFC, y la base que se entrega a cada candidato es propia.

## Antes

| Comprobación | Resultado |
|---|---|
| El script, en un Postgres local cargado solo con `database.sql` | la primera corrida aplica las 5 migraciones; la segunda dice "Schema is up to date" y no cambia nada; sin `--migrate` no toca la estructura; con otra palabra en la confirmación, no hace nada |
| Neon contra `database.sql`, fila por fila | idénticas: 4 usuarios, 66 activos, 126 precios y 11 órdenes, con el contador de órdenes en 11 |
| `unaccent` | disponible y de confianza, y el rol tiene permiso de crear en la base |
| Respaldo | `pg_dump` completo, guardado fuera del repositorio |
| La conexión, sin migrar | la API arrancó contra Neon y devolvió el portfolio del usuario 1 |

## La migración

```bash
./scripts/run-on-neon.sh --migrate
```

Va por la conexión directa, no por el pooler de Neon, y en una sola transacción: todo o nada.

```text
Schema history table "public"."flyway_schema_history" does not exist yet
Successfully validated 5 migrations (execution time 00:00.520s)
Creating Schema History table "public"."flyway_schema_history" with baseline ...
Successfully baselined schema with version: 1
Current version of schema "public": 1
Migrating schema "public" to version "2 - enable unaccent"
Migrating schema "public" to version "3 - add single row rules"
Migrating schema "public" to version "4 - add order identifier fingerprint and rejection reason"
Migrating schema "public" to version "5 - store order time with time zone"
Migrating schema "public" to version "6 - index orders by user and time"
Successfully applied 5 migrations to schema "public", now at version v6 (execution time 00:04.592s)
```

## Después

| Comprobación | Resultado |
|---|---|
| Historial de migraciones | las versiones 1 a 6, todas exitosas |
| Los datos, contra `database.sql` | los mismos: usuarios, activos y precios idénticos; las órdenes, con las mismas columnas y la hora leída en Buenos Aires. La orden 2 sigue a las 12:31 |
| El contador de órdenes | sigue en 11: la próxima orden es la 12 |
| La estructura | `datetime` con zona horaria, las 3 columnas nuevas, `unaccent` instalada y el índice nuevo |
| La API contra Neon, por el pooler, solo con lecturas | portfolio del usuario 1 con $627.500 disponibles y $889.756 de valor total; la búsqueda de "pamp"; las órdenes pendientes 7 y 5; salud `UP` |
