#!/usr/bin/env bash
# Pruebas de Flyway 12.4.0 (RFC, sección 3.6), en una copia local descartable, nunca en Neon.
# Requiere el contenedor cocos-medicion-pg18 solo con database.sql cargado: Flyway aplica 03-esquema.sql como la
# versión 2, igual que haría con Neon.
set -euo pipefail
C=cocos-medicion-pg18
R="$(cd "$(dirname "$0")" && pwd)"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/sql"
cp "$R/03-esquema.sql" "$W/sql/V2__schema_changes_from_the_rfc.sql"
fw() { # $1 base, el resto: argumentos de Flyway
  local base="$1"; shift
  docker run --rm --network "container:$C" -v "$W/sql:/flyway/sql:ro" flyway/flyway:12.4.0 \
    -url="jdbc:postgresql://127.0.0.1:5432/$base" -user=postgres -password=medicion \
    -baselineOnMigrate=true -baselineVersion=1 '-baselineDescription=database.sql de Cocos' "$@" 2>&1 |
    grep -E 'baselined|Migrating schema|Successfully applied|up to date|rolled back|BASELINE|Success  |Failed' || true
}
psql_c() { docker exec -i "$C" psql -U postgres -X -q -At "$@"; }

echo '== 1. Base con los datos de Cocos y sin historial: Flyway anota la versión 1 y aplica la 2'
fw postgres migrate
fw postgres info
echo "   unaccent instalada: $(psql_c -c "select count(*) from pg_extension where extname = 'unaccent'")"
echo "   orden 2: $(psql_c -c "select to_char(datetime at time zone 'America/Argentina/Buenos_Aires', 'YYYY-MM-DD HH24:MI') from orders where id = 2") de Buenos Aires"

echo '== 2. Correrlo otra vez no hace nada'
fw postgres migrate

echo '== 3. Una migración que falla a mitad de camino se deshace entera'
printf 'alter table orders add column probe int;\nalter table orders add constraint probe_check check (size > 1000000000);\n' \
  > "$W/sql/V3__a_migration_that_fails.sql"
fw postgres migrate
echo "   columna agregada antes del error: $(psql_c -c "select count(*) from information_schema.columns where table_name = 'orders' and column_name = 'probe'")"
echo "   historial: $(psql_c -c "select string_agg(version, ', ' order by installed_rank) from flyway_schema_history")"
rm "$W/sql/V3__a_migration_that_fails.sql"

echo '== 4. Sin group: si falla la 4, la 3 queda aplicada'
echo 'alter table orders add column probe_ok int;' > "$W/sql/V3__a_good_migration.sql"
echo 'alter table orders add constraint probe_check check (size > 1000000000);' > "$W/sql/V4__a_migration_that_fails.sql"
fw postgres migrate
echo "   columna de la 3: $(psql_c -c "select count(*) from information_schema.columns where table_name = 'orders' and column_name = 'probe_ok'")"
psql_c -c "alter table orders drop column probe_ok" -c "delete from flyway_schema_history where version = '3'" > /dev/null

echo '== 5. Con group: la 3 y la 4 van en una sola transacción, y se deshacen juntas'
fw postgres migrate -group=true
echo "   columna de la 3: $(psql_c -c "select count(*) from information_schema.columns where table_name = 'orders' and column_name = 'probe_ok'")"
echo "   historial: $(psql_c -c "select string_agg(version, ', ' order by installed_rank) from flyway_schema_history")"
rm "$W/sql/V3__a_good_migration.sql" "$W/sql/V4__a_migration_that_fails.sql"

echo '== 6. Una base vacía, sin database.sql, no se puede migrar'
psql_c -c "create database vacia" > /dev/null
fw vacia migrate
