-- Medición del último precio, de la búsqueda y del borrado de un activo (RFC, sección 3).
-- Corre después de 03-indices.sql, en la misma copia local descartable (nunca en Neon).

\set ON_ERROR_STOP 1
\pset footer off
\timing off
set client_min_messages = warning;
select setseed(0.2026);

create table resultados_precios (consulta text, config text, ms numeric, plan text);

create function medir_p(p_consulta text, p_config text, q text, n int default 7)
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
  insert into resultados_precios values (p_consulta, p_config,
    (select round(percentile_cont(0.5) within group (order by x)::numeric, 3) from unnest(ts) x), indices);
end $$;

-- 1. Último precio, con 10 años de precios diarios (días hábiles) de las 63 acciones que cotizan,
--    cargados antes de los 2 días reales.
insert into marketdata (instrumentid, high, low, open, close, previousclose, date)
select m.instrumentid, round(b * 1.02, 2), round(b * 0.98, 2), round(b, 2), round(b * 1.01, 2), round(b, 2), d::date
from (select distinct instrumentid from marketdata) m,
     generate_series(date '2013-07-15', date '2023-07-12', interval '1 day') d,
     lateral (select (10 + random() * 1000)::numeric as b) x
where extract(isodow from d) < 6;
analyze marketdata;
\echo '== Precios cargados'
select count(*) as filas, pg_size_pretty(pg_relation_size('marketdata')) as tabla from marketdata;

create function medir_ultimo_precio(p_config text) returns void language plpgsql as $$
begin
  perform medir_p('a. último precio de todas las acciones, con DISTINCT ON', p_config,
    'select distinct on (instrumentid) instrumentid, close, previousclose
     from marketdata order by instrumentid, date desc');
  perform medir_p('b. último precio de 20 acciones, buscado acción por acción', p_config,
    'select i.id, m.close, m.previousclose
     from (select id from instruments where id between 1 and 22 and id not in (3, 10)) i
     cross join lateral (select close, previousclose from marketdata
                         where instrumentid = i.id order by date desc limit 1) m');
end $$;
select medir_ultimo_precio('con la regla de unicidad (acción, fecha)');
alter table marketdata drop constraint marketdata_instrument_date_key;
analyze marketdata;
select medir_ultimo_precio('sin índice');
alter table marketdata add constraint marketdata_instrument_date_key unique (instrumentid, date);

-- 2. Búsqueda (reglas de A-5), con los 65 activos y con un catálogo de 10.000.
create function buscar(texto text) returns text language sql as $f$
  select format($q$
    with q as (select lower(unaccent(trim(%L))) as t),
    palabras as (select w from q, regexp_split_to_table(q.t, '\s+') w where w <> '')
    select i.id, i.ticker, i.name from instruments i, q
    where i.type <> 'MONEDA'
      and not exists (select 1 from palabras where strpos(lower(unaccent(i.ticker || ' ' || i.name)), w) = 0)
    order by case when lower(i.ticker) = q.t then 1
                  when lower(i.ticker) like q.t || '%%' then 2
                  when lower(i.ticker) like '%%' || q.t || '%%' then 3
                  when lower(unaccent(i.name)) like q.t || '%%' then 4
                  else 5 end,
             i.ticker collate "C"
    limit 20$q$, texto)
$f$;
select medir_p('c. búsqueda "pa"', '65 activos', buscar('pa'));
select medir_p('d. búsqueda "grupo galicia"', '65 activos', buscar('grupo galicia'));
insert into instruments (ticker, name, type)
select 'T' || g,
       (array['Compañía', 'Grupo', 'Banco', 'Industrias', 'Transportadora', 'Energía'])[1 + g % 6] || ' ' ||
       (array['del Sur', 'Andina', 'Río de la Plata', 'Argentina', 'Patagónica', 'del Norte'])[1 + (g / 6) % 6] ||
       ' ' || g || ' S.A.',
       'ACCIONES'
from generate_series(1, 10000) g;
analyze instruments;
select medir_p('c. búsqueda "pa"', '10.000 activos', buscar('pa'));
select medir_p('d. búsqueda "grupo galicia"', '10.000 activos', buscar('grupo galicia'));
select medir_p('e. búsqueda "rio de la plata"', '10.000 activos', buscar('rio de la plata'));
select medir_p('f. búsqueda "y"', '10.000 activos', buscar('y'));

\echo '== Mediana en ms'
select consulta, config, ms, plan from resultados_precios order by consulta, config;

-- 3. Borrar un activo sin índice en orders.instrumentid: la clave foránea revisa las 2 millones de órdenes.
\echo '== Borrar un activo que ninguna orden usa'
insert into instruments (ticker, name, type) values ('ZZZZ', 'Activo de prueba', 'ACCIONES');
explain (analyze, timing off, costs off) delete from instruments where ticker = 'ZZZZ';
