#!/usr/bin/env bash
# Las trampas que aparecieron al probar los cambios de la sección 3 del RFC.
# Corre en una copia local descartable, nunca en Neon, solo con database.sql cargado (contenedor cocos-medicion-pg18).
# Cada prueba va en una transacción que se deshace, así que no cambia nada, y corre en su propio psql para que la
# salida quede en orden.
set -uo pipefail
C=cocos-medicion-pg18
prueba() { # $1 título, stdin: el SQL
  echo "$1"
  docker exec -i "$C" psql -U postgres -X -q -At 2>&1 | sed 's/^/   /'
}

prueba '== 1. Una regla NOT VALID se vuelve a verificar al modificar una fila vieja (sección 3.2): cancelar la orden 7, vieja y sin identificador' <<'SQL'
begin;
alter table orders add column clientOrderId uuid;
alter table orders add constraint orders_clientorderid_required check (clientOrderId is not null) not valid;
update orders set status = 'CANCELLED' where id = 7 and userid = 1 and status = 'NEW';
rollback;
SQL

prueba '== 2. La base rechaza un lado que no existe (sección 3.2)' <<'SQL'
begin;
alter table orders add constraint orders_side_check check (side in ('BUY', 'SELL', 'CASH_IN', 'CASH_OUT'));
insert into orders (instrumentid, userid, size, price, type, side, status, datetime)
values (47, 1, 10, 925.85, 'MARKET', 'COMPRA', 'FILLED', now());
rollback;
SQL

prueba '== 3. La conversión de la hora tiene que nombrar la zona (sección 3.4); Neon está en GMT. Con la zona escrita:' <<'SQL'
begin;
set local timezone = 'GMT';
alter table orders alter column datetime type timestamptz using datetime at time zone 'America/Argentina/Buenos_Aires';
select 'orden 2 a las ' || to_char(datetime at time zone 'America/Argentina/Buenos_Aires', 'HH24:MI') || ' de Buenos Aires'
from orders where id = 2;
rollback;
SQL

prueba '   Con el cast simple:' <<'SQL'
begin;
set local timezone = 'GMT';
alter table orders alter column datetime type timestamptz using datetime::timestamptz;
select 'orden 2 a las ' || to_char(datetime at time zone 'America/Argentina/Buenos_Aires', 'HH24:MI') || ' de Buenos Aires'
from orders where id = 2;
rollback;
SQL

prueba '== 4. now() queda fija en la hora en que empezó la transacción; clock_timestamp() avanza (sección 3.4)' <<'SQL'
begin;
select now() as t0, clock_timestamp() as r0 \gset
select pg_sleep(1.5) \gset
select 'now() quedó fija: ' || (now() = :'t0'::timestamptz) ||
       '; el reloj avanzó ' || round(extract(epoch from clock_timestamp() - :'r0'::timestamptz)::numeric, 1) || ' s';
rollback;
SQL

prueba '== 5. Un precio de más de $99.999.999,99 hace fallar a la base (sección 3.2)' <<'SQL'
select 100000000.00::numeric(10, 2);
SQL

prueba '== 6. Una cantidad de más de 2.147.483.647 hace fallar a la base (sección 3.2)' <<'SQL'
select 2147483648::int;
SQL
