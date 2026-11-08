-- Vuelta a los datos originales antes de cada test (RFC, sección 10).
-- Corre en una copia local descartable, nunca en Neon, con database.sql y 03-esquema.sql ya aplicados y sin los datos
-- de 03-indices.sql.

\set ON_ERROR_STOP 1
\pset footer off
-- Foto de los datos originales, una vez, después de las migraciones
create schema originales;
create table originales.instruments as table instruments;
create table originales.users as table users;
create table originales.marketdata as table marketdata;
create table originales.orders as table orders;
-- La vuelta a los datos originales que correría antes de cada test
create function volver_a_los_originales() returns void language plpgsql as $$
begin
  truncate orders, marketdata, users, instruments;
  insert into instruments select * from originales.instruments;
  insert into users select * from originales.users;
  insert into marketdata select * from originales.marketdata;
  insert into orders select * from originales.orders;
  perform setval('instruments_id_seq', 66), setval('users_id_seq', 4), setval('marketdata_id_seq', 126),
          setval('orders_id_seq', 11);
end $$;
-- Un test que escribe: cancela la orden 7, crea un usuario y una orden
update orders set status = 'CANCELLED' where id = 7;
insert into users (email, accountnumber) values ('t@test.local', '30001');
insert into orders (instrumentid, userid, size, price, type, side, status, clientOrderId, requestFingerprint)
values (47, 1, 10, 925.85, 'MARKET', 'BUY', 'FILLED', gen_random_uuid(), md5('x') || md5('x'));
select volver_a_los_originales();
\echo '== Después de volver: la orden 7, los conteos y el próximo id de orden'
select (select status from orders where id = 7) as orden_7, (select count(*) from orders) as ordenes,
       (select count(*) from users) as usuarios, nextval('orders_id_seq') as proximo_id_de_orden;
select setval('orders_id_seq', 11);
\echo '== Tiempo de 50 vueltas a los originales'
do $$ declare t0 timestamptz := clock_timestamp(); begin
  for i in 1..50 loop perform volver_a_los_originales(); end loop;
  raise notice 'promedio por vuelta: % ms', round(extract(epoch from clock_timestamp() - t0)::numeric * 1000 / 50, 1);
end $$;
