-- Medición de la consulta del posible duplicado (RFC, sección 5.5), con dos relojes para la ventana.
-- Corre en la copia local levantada con docker compose y con perf/seed-volume.sql ya cargado, nunca en Neon.
-- Busca una orden igual del usuario pesado (10005, con 10.000 órdenes) en los últimos 5 segundos, y no hay
-- ninguna: es el caso normal, el de una orden que no es un duplicado.
-- Son números de una PC con los datos en memoria: sirven como orden de magnitud.

\set ON_ERROR_STOP 1
\pset pager off
\pset tuples_only on
\pset format unaligned

-- Lee las órdenes del usuario una vez antes de medir, para que las tres formas encuentren sus páginas en memoria.
-- Cuenta una columna que no está en el índice: así lee también las filas de la tabla.
select count(requestfingerprint) from orders where userid = 10005 \g /dev/null

\echo == A. Con clock_timestamp(), que es volátil
explain (analyze, buffers, costs off, timing off)
select id from orders
where userid = 10005
  and datetime > clock_timestamp() - 5000 * interval '1 millisecond'
  and requestfingerprint = repeat('a', 64)
  and clientorderid <> '00000000-0000-4000-8000-000000000000'
  and status in ('FILLED', 'NEW')
order by datetime desc, id desc
limit 1;

\echo
\echo == B. Con statement_timestamp(), que es estable: la que usa la API
explain (analyze, buffers, costs off, timing off)
select id from orders
where userid = 10005
  and datetime > statement_timestamp() - 5000 * interval '1 millisecond'
  and requestfingerprint = repeat('a', 64)
  and clientorderid <> '00000000-0000-4000-8000-000000000000'
  and status in ('FILLED', 'NEW')
order by datetime desc, id desc
limit 1;

\echo
\echo == C. La forma B con el plan genérico, el que Postgres puede elegir cuando una consulta preparada se repite
prepare lookup(bigint, bigint, text, uuid, text, text) as
  select id from orders
  where userid = $1
    and datetime > statement_timestamp() - $2 * interval '1 millisecond'
    and requestfingerprint = $3
    and clientorderid <> $4
    and status in ($5, $6)
  order by datetime desc, id desc
  limit 1;
set plan_cache_mode = force_generic_plan;
explain (analyze, buffers, costs off, timing off)
execute lookup(10005, 5000, repeat('a', 64), '00000000-0000-4000-8000-000000000000', 'FILLED', 'NEW');
