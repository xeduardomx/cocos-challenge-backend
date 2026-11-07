# Resultados de las pruebas de la sección 3 del RFC

> Corrida del 2026-11-07 en una laptop, con Postgres 18.6 en Docker y Flyway 12.4.0. Cada script corre en una copia
> nueva, solo con `database.sql` cargado (nunca en Neon): [`03-trampas.sh`](03-trampas.sh) deshace cada prueba, y
> [`03-flyway.sh`](03-flyway.sh) le aplica a esa copia `03-esquema.sql` como la versión 2, igual que haría con Neon.

## Trampas de las reglas y de la hora

```text
== 1. Una regla NOT VALID se vuelve a verificar al modificar una fila vieja (sección 3.2): cancelar la orden 7, vieja y sin identificador
   ERROR:  new row for relation "orders" violates check constraint "orders_clientorderid_required"
   DETAIL:  Failing row contains (7, 31, 1, 60, 1500.00, LIMIT, BUY, CANCELLED, 2023-07-13 11:13:20, null).
== 2. La base rechaza un lado que no existe (sección 3.2)
   ERROR:  new row for relation "orders" violates check constraint "orders_side_check"
   DETAIL:  Failing row contains (12, 47, 1, 10, 925.85, MARKET, COMPRA, FILLED, 2026-11-07 17:07:06.988767).
== 3. La conversión de la hora tiene que nombrar la zona (sección 3.4); Neon está en GMT. Con la zona escrita:
   orden 2 a las 12:31 de Buenos Aires
   Con el cast simple:
   orden 2 a las 09:31 de Buenos Aires
== 4. now() queda fija en la hora en que empezó la transacción; clock_timestamp() avanza (sección 3.4)
   now() quedó fija: true; el reloj avanzó 1.5 s
== 5. Un precio de más de $99.999.999,99 hace fallar a la base (sección 3.2)
   ERROR:  numeric field overflow
   DETAIL:  A field with precision 10, scale 2 must round to an absolute value less than 10^8.
== 6. Una cantidad de más de 2.147.483.647 hace fallar a la base (sección 3.2)
   ERROR:  integer out of range
```

## Flyway

```text
== 1. Base con los datos de Cocos y sin historial: Flyway anota la versión 1 y aplica la 2
Successfully baselined schema with version: 1
Migrating schema "public" to version "2 - schema changes from the rfc"
Successfully applied 1 migration to schema "public", now at version v2 (execution time 00:00.014s)
|           | 1       | database.sql de Cocos       | BASELINE | 2026-11-07 17:06:24 | Baseline | No       |
| Versioned | 2       | schema changes from the rfc | SQL      | 2026-11-07 17:06:24 | Success  | No       |
   unaccent instalada: 1
   orden 2: 2023-07-12 12:31 de Buenos Aires
== 2. Correrlo otra vez no hace nada
Schema "public" is up to date. No migration necessary.
== 3. Una migración que falla a mitad de camino se deshace entera
Migrating schema "public" to version "3 - a migration that fails"
ERROR: Migration of schema "public" to version "3 - a migration that fails" failed! Changes successfully rolled back.
ERROR: Failed to execute script V3__a_migration_that_fails.sql
Caused by: Failed to execute script V3__a_migration_that_fails.sql
   columna agregada antes del error: 0
   historial: 1, 2
== 4. Sin group: si falla la 4, la 3 queda aplicada
Migrating schema "public" to version "3 - a good migration"
Migrating schema "public" to version "4 - a migration that fails"
ERROR: Migration of schema "public" to version "4 - a migration that fails" failed! Changes successfully rolled back.
ERROR: Failed to execute script V4__a_migration_that_fails.sql
Caused by: Failed to execute script V4__a_migration_that_fails.sql
   columna de la 3: 1
== 5. Con group: la 3 y la 4 van en una sola transacción, y se deshacen juntas
Migrating schema "public" to version "3 - a good migration"
Migrating schema "public" to version "4 - a migration that fails"
ERROR: Migration of schema "public" to version "4 - a migration that fails" failed! Changes successfully rolled back.
ERROR: Failed to execute script V4__a_migration_that_fails.sql
Caused by: Failed to execute script V4__a_migration_that_fails.sql
   columna de la 3: 0
   historial: 1, 2
== 6. Una base vacía, sin database.sql, no se puede migrar
Migrating schema "public" to version "2 - schema changes from the rfc"
ERROR: Migration of schema "public" to version "2 - schema changes from the rfc" failed! Changes successfully rolled back.
ERROR: Failed to execute script V2__schema_changes_from_the_rfc.sql
Caused by: Failed to execute script V2__schema_changes_from_the_rfc.sql
```
