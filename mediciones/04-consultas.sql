-- Medición de la forma de la consulta del último precio (RFC, sección 4).
-- Corre en una copia local descartable, nunca en Neon, con database.sql y 03-esquema.sql ya aplicados.
-- Carga 10 años de precios diarios de las 63 acciones que cotizan y compara tres formas de pedir el último precio
-- de 20 acciones. Son números de una PC con los datos en memoria: sirven como orden de magnitud.

\set ON_ERROR_STOP 1
\pset footer off
\timing off
set client_min_messages = warning;
select setseed(0.2026);

insert into marketdata (instrumentid, high, low, open, close, previousclose, date)
select m.instrumentid, round(b * 1.02, 2), round(b * 0.98, 2), round(b, 2), round(b * 1.01, 2), round(b, 2), d::date
from (select distinct instrumentid from marketdata) m,
     generate_series(date '2013-07-15', date '2023-07-12', interval '1 day') d,
     lateral (select (10 + random() * 1000)::numeric as b) x
where extract(isodow from d) < 6;
analyze marketdata;

create table resultados_consultas (consulta text, ms numeric, plan text);

create function medir_c(p_consulta text, q text, n int default 7) returns void language plpgsql as $$
declare
  plan jsonb;
  ts numeric[] := '{}';
  indices text;
begin
  for i in 1..n loop
    execute 'explain (analyze, timing off, buffers off, format json) ' || q into plan;
    ts := ts || (plan->0->>'Execution Time')::numeric;
  end loop;
  select coalesce(string_agg(distinct v #>> '{}', ' + '), '') into indices
  from jsonb_path_query(plan, 'strict $.**."Index Name"') v;
  if plan::text like '%"Seq Scan"%' then
    indices := trim(both ' +' from indices || ' + tabla entera');
  end if;
  insert into resultados_consultas values (p_consulta,
    (select round(percentile_cont(0.5) within group (order by x)::numeric, 3) from unnest(ts) x), indices);
end $$;

-- Las 20 acciones: ids 1 a 22 sin PGR (3) ni IRCP (10), que no cotizan.
select medir_c('a. DISTINCT ON sobre toda la tabla',
  'select distinct on (instrumentid) instrumentid, close, previousclose
   from marketdata order by instrumentid, date desc');
select medir_c('b. DISTINCT ON solo de las 20 acciones',
  'select distinct on (instrumentid) instrumentid, close, previousclose
   from marketdata where instrumentid = any (array[1,2,4,5,6,7,8,9,11,12,13,14,15,16,17,18,19,20,21,22])
   order by instrumentid, date desc');
select medir_c('c. acción por acción (LATERAL ... LIMIT 1)',
  'select i.id, m.close, m.previousclose
   from unnest(array[1,2,4,5,6,7,8,9,11,12,13,14,15,16,17,18,19,20,21,22]) as i(id)
   cross join lateral (select close, previousclose from marketdata
                       where instrumentid = i.id order by date desc limit 1) m');

\echo '== Último precio de 20 acciones, con 10 años de precios (mediana en ms)'
select count(*) as filas_de_precios from marketdata;
select consulta, ms, plan from resultados_consultas order by consulta;
