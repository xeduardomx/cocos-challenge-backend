#!/usr/bin/env bash
# Pruebas de órdenes simultáneas (RFC, sección 5), en una copia local descartable, nunca en Neon.
# Requiere el contenedor cocos-medicion-pg18 en una copia nueva, con database.sql y 03-esquema.sql y sin los datos de
# 03-indices.sql: el usuario de prueba tiene que recibir el id 5 (ver RFC, sección 3.5).
#
# Cada prueba manda dos pedidos casi a la vez, el segundo 0,3 segundos después del primero. Cada pedido lee la cuenta,
# espera 1 segundo (lo que tardaría la API en calcular) y graba. Simula en SQL lo que hace la API.
set -euo pipefail
C=cocos-medicion-pg18
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
psql_c() { docker exec -i "$C" psql -U postgres -X -q -At "$@"; }

# Usuario 5, de prueba: deposita $1.000.000 y compra 20 BMA (instrumento 31).
psql_c -v ON_ERROR_STOP=1 <<'SQL'
insert into users (email, accountnumber) values ('prueba@test.local', '20001');
insert into orders (instrumentid, userid, size, price, type, side, status, clientOrderId, requestFingerprint) values
 (66, 5, 1000000, 1, 'MARKET', 'CASH_IN', 'FILLED', gen_random_uuid(), md5('a') || md5('a')),
 (31, 5, 20, 1502.80, 'MARKET', 'BUY', 'FILLED', gen_random_uuid(), md5('b') || md5('b'));
SQL

# Una venta de 15 BMA: con o sin turno (lock de la fila del usuario), en el nivel de aislamiento que se pida.
cat > "$W/venta.sql" <<'SQL'
\set ON_ERROR_STOP 1
begin isolation level :isolation;
\if :turno
select id as turno_tomado from users where id = 5 for update \gset
\endif
select coalesce(sum(case when side = 'BUY' then size when side = 'SELL' then -size end), 0) as posicion
from orders where userid = 5 and instrumentid = 31 and status = 'FILLED' \gset
select pg_sleep(1) \gset
insert into orders (instrumentid, userid, size, price, type, side, status, clientOrderId, requestFingerprint, rejectionReason)
values (31, 5, 15, 1502.80, 'MARKET', 'SELL', case when :posicion >= 15 then 'FILLED' else 'REJECTED' end,
        gen_random_uuid(), md5(random()::text) || md5(random()::text),
        case when :posicion >= 15 then null else 'INSUFFICIENT_SHARES' end)
returning status || ' (vio ' || :posicion || ')';
commit;
SQL

# Un pedido con un identificador fijo: si ya existe, devuelve la orden original; si no, la crea.
cat > "$W/reintento.sql" <<'SQL'
\set ON_ERROR_STOP 1
begin;
\if :turno
select id as turno_tomado from users where id = 5 for update \gset
\endif
select count(*) as existe from orders where userid = 5 and clientOrderId = '7d1c0b9e-4f2a-4a5e-9b7c-1e2d3f4a5b6c' \gset
select pg_sleep(1) \gset
\if :existe
select 'devuelve la orden original' from orders where userid = 5 and clientOrderId = '7d1c0b9e-4f2a-4a5e-9b7c-1e2d3f4a5b6c';
\else
insert into orders (instrumentid, userid, size, price, type, side, status, clientOrderId, requestFingerprint)
values (47, 5, 1, 925.85, 'MARKET', 'BUY', 'FILLED', '7d1c0b9e-4f2a-4a5e-9b7c-1e2d3f4a5b6c', md5('c') || md5('c'))
returning 'crea la orden';
\endif
commit;
SQL

# Cancelar la orden 7 en un solo paso: solo si sigue NEW.
cat > "$W/cancelar.sql" <<'SQL'
\set ON_ERROR_STOP 1
begin;
update orders set status = 'CANCELLED' where id = 7 and userid = 1 and status = 'NEW' returning 'cancela la orden 7';
select pg_sleep(1) \gset
commit;
SQL

par() { # $1 script, $2 aislamiento, $3 turno (0 o 1)
  psql_c -v isolation="$2" -v turno="$3" < "$W/$1" > "$W/p1.out" 2>&1 &
  local p1=$!
  sleep 0.3
  psql_c -v isolation="$2" -v turno="$3" < "$W/$1" > "$W/p2.out" 2>&1 || true
  wait "$p1" || true
  r1="$(grep -v '^$' "$W/p1.out" | head -1 | cut -c1-80 || true)"
  r2="$(grep -v '^$' "$W/p2.out" | head -1 | cut -c1-80 || true)"
  r1="${r1:-no cambia ninguna fila}"
  r2="${r2:-no cambia ninguna fila}"
}
posicion() { psql_c -c "select sum(case when side = 'BUY' then size else -size end) from orders
                        where userid = 5 and instrumentid = 31 and status = 'FILLED'"; }
cuantas() { psql_c -c "select count(*) from orders where clientOrderId = '7d1c0b9e-4f2a-4a5e-9b7c-1e2d3f4a5b6c'"; }

echo '== Dos ventas de 15 BMA a la vez, teniendo 20'
for e in 'A. sin turno, READ COMMITTED|read committed|0' \
         'B. con turno, READ COMMITTED|read committed|1' \
         'C. con turno, REPEATABLE READ|repeatable read|1'; do
  IFS='|' read -r nombre aislamiento turno <<< "$e"
  psql_c -c "delete from orders where userid = 5 and side = 'SELL'"
  par venta.sql "$aislamiento" "$turno"
  echo "$nombre | venta 1: $r1 | venta 2: $r2 | posición final: $(posicion)"
done

echo '== Dos pedidos a la vez con el mismo identificador'
for e in 'D. sin turno|0' 'E. con turno|1'; do
  IFS='|' read -r nombre turno <<< "$e"
  psql_c -c "delete from orders where userid = 5 and clientOrderId = '7d1c0b9e-4f2a-4a5e-9b7c-1e2d3f4a5b6c'"
  par reintento.sql 'read committed' "$turno"
  echo "$nombre | pedido 1: $r1 | pedido 2: $r2 | órdenes con ese identificador: $(cuantas)"
done

echo '== Dos cancelaciones a la vez de la orden 7'
par cancelar.sql 'read committed' 0
echo "F. un solo UPDATE condicionado | pedido 1: $r1 | pedido 2: $r2 | estado final: $(psql_c -c 'select status from orders where id = 7')"
