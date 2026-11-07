-- Medición de los índices de `orders` (RFC, sección 3).
-- Corre en una copia local descartable, nunca en Neon, con database.sql y 03-esquema.sql ya aplicados.
-- Tarda unos minutos. Son números de una laptop con los datos en memoria: sirven como orden de magnitud.
--
-- Datos inventados: 10.000 usuarios típicos (100 órdenes cada uno), uno pesado (10.000) y uno extremo
-- (1.000.000), que sirve para ver dónde está el límite. En total, 2.010.011 órdenes con las 11 de Cocos.

\set ON_ERROR_STOP 1
\pset footer off
\timing off
set client_min_messages = warning;
select setseed(0.2026);

-- 1. Sin los índices de orders, para cargar más rápido. Cada combinación los vuelve a crear.
alter table orders drop constraint orders_user_clientorderid_key;
drop index orders_user_datetime_idx;

-- 2. Datos. Usuarios 5 a 10004: típicos; 10005: pesado; 10006: extremo.
--    Cada usuario arranca con un depósito y tiene unas 5 órdenes pendientes entre las 50 más nuevas.
insert into users (email, accountnumber)
select 'u' || g || '@test.local', (20000 + g)::text from generate_series(1, 10002) g;

with px as (
  select array_agg(instrumentid order by instrumentid) as ids, array_agg(close order by instrumentid) as closes
  from (select distinct on (instrumentid) instrumentid, close from marketdata order by instrumentid, date desc) t
), usuarios as (
  select id as userid, case id when 10005 then 10000 when 10006 then 1000000 else 100 end as cnt
  from users where id >= 5
), filas as (
  select u.userid, u.cnt, g as n, 1 + floor(random() * 63)::int as idx, random() as r_status,
         random() as r_side, random() as r_type, random() as r_new, 1 + floor(random() * 100)::int as qty,
         random() as r_us
  from usuarios u, generate_series(1, u.cnt) g
), f2 as (
  select f.*,
         case when f.n = 1 then 'FILLED'
              when f.n > f.cnt - 50 and f.r_new < 0.1 then 'NEW'
              when f.r_status < 0.85 then 'FILLED'
              when f.r_status < 0.93 then 'REJECTED'
              else 'CANCELLED' end as st,
         case when f.n = 1 then 'CASH_IN' when f.r_side < 0.6 then 'BUY' else 'SELL' end as sd
  from filas f
)
insert into orders (instrumentid, userid, size, price, type, side, status, datetime,
                    clientOrderId, requestFingerprint, rejectionReason)
select case when f.n = 1 then 66 else px.ids[f.idx] end,
       f.userid,
       case when f.n = 1 then 1000000 else f.qty end,
       case when f.n = 1 then 1 else px.closes[f.idx] end,
       case when f.st in ('NEW', 'CANCELLED') then 'LIMIT' when f.n = 1 or f.r_type < 0.7 then 'MARKET' else 'LIMIT' end,
       f.sd,
       f.st,
       timestamptz '2023-07-14 11:00:00-03' + (f.n::float8 / f.cnt) * interval '1095 days' + f.r_us * interval '1 second',
       gen_random_uuid(),
       md5(random()::text) || md5(random()::text),
       case when f.st = 'REJECTED' then case when f.sd = 'BUY' then 'INSUFFICIENT_CASH' else 'INSUFFICIENT_SHARES' end end
from f2 f cross join px;
vacuum analyze orders;

\echo '== Datos generados'
select count(*) as ordenes, pg_size_pretty(pg_relation_size('orders')) as tabla from orders;
select userid, count(*) as ordenes, count(*) filter (where status = 'NEW') as pendientes,
       count(*) filter (where status in ('FILLED', 'NEW')) as ejecutadas_y_pendientes
from orders where userid in (5000, 10005, 10006) group by userid order by userid;

-- 3. Funciones de medición
create table cursores as  -- cursor de la última página: la orden 21 desde la más vieja
select userid, datetime, id from (
  select userid, datetime, id, row_number() over (partition by userid order by datetime, id) as rn
  from orders where userid in (5000, 10005, 10006)) t
where rn = 21;

create table resultados (config text, consulta text, usuario text, ms numeric, plan text);

-- Corre una consulta n veces con EXPLAIN ANALYZE y guarda la mediana y los índices que usó.
create function medir(p_config text, p_consulta text, p_usuario text, q text, n int default 7)
returns void language plpgsql as $$
declare
  plan jsonb;
  ts numeric[] := '{}';
  indices text;
begin
  for i in 1..n loop
    execute 'explain (analyze, timing off, format json) ' || q into plan;
    ts := ts || (plan->0->>'Execution Time')::numeric;
  end loop;
  select coalesce(string_agg(distinct v #>> '{}', ' + '), '') into indices
  from jsonb_path_query(plan, 'strict $.**."Index Name"') v;
  if plan::text like '%"Seq Scan"%' then
    indices := trim(both ' +' from indices || ' + tabla entera');
  end if;
  insert into resultados values (p_config, p_consulta, p_usuario,
    (select round(percentile_cont(0.5) within group (order by x)::numeric, 3) from unnest(ts) x), indices);
end $$;

-- Las consultas que la API hace sobre orders, para cada usuario medido.
create function medir_consultas(p_config text) returns void language plpgsql as $$
declare
  u record;
  cols text := 'id, instrumentid, side, type, size, price, status, datetime, clientOrderId, rejectionReason';
begin
  for u in select c.userid, c.datetime, c.id,
                  case c.userid when 5000 then '1 típico (100)' when 10005 then '2 pesado (10.000)'
                                else '3 extremo (1.000.000)' end as nombre
           from cursores c order by c.userid loop
    perform medir(p_config, '1. portfolio: FILLED y NEW en orden cronológico', u.nombre,
      format('select instrumentid, side, status, size, price, datetime, id from orders
              where userid = %s and status in (''FILLED'', ''NEW'') order by datetime, id', u.userid),
      case when u.userid = 10006 then 3 else 7 end);
    perform medir(p_config, '2. listado: primera página', u.nombre,
      format('select %s from orders where userid = %s order by datetime desc, id desc limit 21', cols, u.userid));
    perform medir(p_config, '3. listado: última página (cursor)', u.nombre,
      format('select %s from orders where userid = %s and (datetime, id) < (%L::timestamptz, %s)
              order by datetime desc, id desc limit 21', cols, u.userid, u.datetime, u.id));
    perform medir(p_config, '4. listado: pendientes (NEW)', u.nombre,
      format('select %s from orders where userid = %s and status = ''NEW''
              order by datetime desc, id desc limit 21', cols, u.userid));
    perform medir(p_config, '5. listado: rechazadas, primera página', u.nombre,
      format('select %s from orders where userid = %s and status = ''REJECTED''
              order by datetime desc, id desc limit 21', cols, u.userid));
  end loop;
end $$;

-- Alta de 100.000 órdenes en una transacción que se deshace; 5 veces, con la mediana.
-- Antes de cada combinación se corre VACUUM: las altas deshechas dejan filas muertas.
create function medir_altas(p_config text, n int default 5) returns void language plpgsql as $$
declare
  t0 timestamptz;
  ts numeric[] := '{}';
begin
  for i in 1..n loop
    begin
      t0 := clock_timestamp();
      insert into orders (instrumentid, userid, size, price, type, side, status,
                          clientOrderId, requestFingerprint, rejectionReason)
      select 47, 5 + (g % 10000), 1 + g % 100, 925.85,
             case when g % 20 in (0, 1) then 'LIMIT' else 'MARKET' end, 'BUY',
             case g % 20 when 0 then 'NEW' when 1 then 'CANCELLED' when 2 then 'REJECTED' else 'FILLED' end,
             gen_random_uuid(), md5(g::text) || md5(g::text),
             case when g % 20 = 2 then 'INSUFFICIENT_CASH' end
      from generate_series(1, 100000) g;
      raise exception using errcode = 'P0001',
        message = round(extract(epoch from clock_timestamp() - t0)::numeric * 1000, 0)::text;
    exception when sqlstate 'P0001' then
      ts := ts || sqlerrm::numeric;
    end;
  end loop;
  insert into resultados values (p_config, '6. alta de una orden, en microsegundos', 'todos',
    round((select percentile_cont(0.5) within group (order by x) from unnest(ts) x)::numeric / 100, 1),
    'corridas de 100.000 (ms): ' || array_to_string(ts, ', '));
end $$;

-- 4. Las combinaciones
select medir_consultas('0. sin índices (como hoy)');

alter table orders add constraint orders_user_clientorderid_key unique (userid, clientOrderId);
analyze orders;
select medir_consultas('1. solo el único del identificador (R-2)');
vacuum orders;
select medir_altas('1. solo el único del identificador (R-2)');

create index orders_user_datetime_idx on orders (userid, datetime, id);
analyze orders;
select medir_consultas('A. + (usuario, fecha, id): la elegida');
vacuum orders;
select medir_altas('A. + (usuario, fecha, id): la elegida');

create index orders_user_new_idx on orders (userid, datetime, id) where status = 'NEW';
analyze orders;
select medir_consultas('D. A + parcial de pendientes');
vacuum orders;
select medir_altas('D. A + parcial de pendientes');

drop index orders_user_new_idx;
create index orders_user_status_datetime_idx on orders (userid, status, datetime, id);
analyze orders;
select medir_consultas('C. A + (usuario, estado, fecha, id), la de A-7');
vacuum orders;
select medir_altas('C. A + (usuario, estado, fecha, id), la de A-7');

drop index orders_user_datetime_idx;
analyze orders;
select medir_consultas('B. solo (usuario, estado, fecha, id)');

-- 5. Tamaños, con todos los índices medidos a la vez. REINDEX los reconstruye: las altas deshechas
--    inflan los índices que ya existían.
create index orders_user_datetime_idx on orders (userid, datetime, id);
create index orders_user_new_idx on orders (userid, datetime, id) where status = 'NEW';
reindex table orders;
analyze orders;
\echo '== Tamaño de cada índice'
select c.relname as indice, pg_size_pretty(pg_relation_size(c.oid)) as tamanio
from pg_class c join pg_index i on i.indexrelid = c.oid
where i.indrelid = 'orders'::regclass
order by pg_relation_size(c.oid) desc;

-- 6. El índice parcial y el plan genérico. Cuando una consulta se repite mucho, Postgres puede dejar de
--    armar un plan por pedido y usar uno general, que no sabe qué estado se pide.
drop index orders_user_status_datetime_idx;  -- quedan el de fecha y el parcial
set plan_cache_mode = force_generic_plan;
prepare por_parametro(int, text) as
  select id from orders where userid = $1 and status = $2 order by datetime desc, id desc limit 21;
prepare con_new_escrito(int) as
  select id from orders where userid = $1 and status = 'NEW' order by datetime desc, id desc limit 21;
\echo '== Pendientes del usuario extremo con plan genérico, con el índice de fecha y el parcial'
\echo '-- 1. con el estado como parámetro: el plan genérico no usa el parcial'
explain (analyze, timing off, costs off) execute por_parametro(10006, 'NEW');
\echo '-- 2. con NEW escrito en el SQL: lo usa'
explain (analyze, timing off, costs off) execute con_new_escrito(10006);
\echo '-- 3. solo con el índice de A-7: sirve aunque el estado sea un parámetro'
create index orders_user_status_datetime_idx on orders (userid, status, datetime, id);
drop index orders_user_datetime_idx, orders_user_new_idx;
explain (analyze, timing off, costs off) execute por_parametro(10006, 'NEW');
deallocate all;
reset plan_cache_mode;

-- 7. Vuelta al esquema propuesto: el único del identificador y (usuario, fecha, id)
drop index orders_user_status_datetime_idx;
create index orders_user_datetime_idx on orders (userid, datetime, id);
analyze orders;

-- 8. Resultados: mediana en ms (las altas, en microsegundos por orden)
\echo '== Mediana en ms por combinación'
select consulta, usuario,
       max(ms) filter (where config like '0.%') as "0 sin índices",
       max(ms) filter (where config like '1.%') as "1 solo R-2",
       max(ms) filter (where config like 'A.%') as "A elegida",
       max(ms) filter (where config like 'B.%') as "B solo estado",
       max(ms) filter (where config like 'C.%') as "C A + A-7",
       max(ms) filter (where config like 'D.%') as "D A + parcial"
from resultados
group by consulta, usuario
order by consulta, usuario;

\echo '== Índices que usó cada consulta del usuario extremo'
select consulta, config, plan from resultados where usuario like '3%' order by consulta, config;
\echo '== Altas: corridas'
select config, ms as micros_por_orden, plan as corridas from resultados where consulta like '6.%' order by config;
