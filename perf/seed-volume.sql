-- Volume data to measure the response time (GEN-8, RFC 8.4), for the database already migrated by
-- the API. The data part of docs/mediciones/03-indices.sql, the same data set as RFC 3.5:
-- 10,000 typical users of 100 orders (ids 5 to 10004), a heavy one of 10,000 (10005) and an
-- extreme one of 1,000,000 (10006). With the 11 orders of Cocos, 2,010,011 orders in total.

\set ON_ERROR_STOP 1
\pset footer off
\timing off
set client_min_messages = warning;

-- RFC 3.5 starts each user with 1,000,000: enough for its queries, not for this measurement. The seeded
-- orders are random and ignore the balance, so over a third of the typical users and the heavy one
-- end up with negative available cash, and their purchases would be rejected instead of executed
-- (docs/mediciones/06-tiempo-de-respuesta.md). Only this amount changes: the random sequence, and so
-- every generated order, stays the same as in RFC 3.5.
\set initial_deposit 100000000

select setseed(0.2026) \g /dev/null

-- The two indexes of the API over orders, dropped to load faster and recreated below.
alter table orders drop constraint orders_user_clientorderid_key;
drop index orders_user_datetime_idx;

insert into users (email, accountnumber)
select 'u' || g || '@test.local', (20000 + g)::text from generate_series(1, 10002) g;

-- Each user starts with a deposit and has about 5 pending orders among the 50 newest ones.
with last_prices as (
  select array_agg(instrumentid order by instrumentid) as ids, array_agg(close order by instrumentid) as closes
  from (select distinct on (instrumentid) instrumentid, close from marketdata order by instrumentid, date desc) t
), seeded_users as (
  select id as userid, case id when 10005 then 10000 when 10006 then 1000000 else 100 end as cnt
  from users where id >= 5
), generated as (
  select u.userid, u.cnt, g as n, 1 + floor(random() * 63)::int as idx, random() as r_status,
         random() as r_side, random() as r_type, random() as r_new, 1 + floor(random() * 100)::int as qty,
         random() as r_us
  from seeded_users u, generate_series(1, u.cnt) g
), with_status as (
  select f.*,
         case when f.n = 1 then 'FILLED'
              when f.n > f.cnt - 50 and f.r_new < 0.1 then 'NEW'
              when f.r_status < 0.85 then 'FILLED'
              when f.r_status < 0.93 then 'REJECTED'
              else 'CANCELLED' end as st,
         case when f.n = 1 then 'CASH_IN' when f.r_side < 0.6 then 'BUY' else 'SELL' end as sd
  from generated f
)
insert into orders (instrumentid, userid, size, price, type, side, status, datetime,
                    clientOrderId, requestFingerprint, rejectionReason)
select case when f.n = 1 then 66 else p.ids[f.idx] end,
       f.userid,
       case when f.n = 1 then :initial_deposit else f.qty end,
       case when f.n = 1 then 1 else p.closes[f.idx] end,
       case when f.st in ('NEW', 'CANCELLED') then 'LIMIT' when f.n = 1 or f.r_type < 0.7 then 'MARKET' else 'LIMIT' end,
       f.sd,
       f.st,
       timestamptz '2023-07-14 11:00:00-03' + (f.n::float8 / f.cnt) * interval '1095 days' + f.r_us * interval '1 second',
       gen_random_uuid(),
       md5(random()::text) || md5(random()::text),
       case when f.st = 'REJECTED' then case when f.sd = 'BUY' then 'INSUFFICIENT_CASH' else 'INSUFFICIENT_SHARES' end end
from with_status f cross join last_prices p;

-- Same names as migrations V4 and V6.
alter table orders add constraint orders_user_clientorderid_key unique (userid, clientOrderId);
create index orders_user_datetime_idx on orders (userid, datetime, id);
vacuum analyze orders;

-- Fails the run if the data is not exactly the expected one.
do $$
begin
  assert (select count(*) from orders) = 2010011, 'unexpected total of orders';
  assert (select count(*) from orders where userid = 5000) = 100, 'a typical user must have 100 orders';
  assert (select count(*) from orders where userid = 10005) = 10000, 'the heavy user must have 10000 orders';
  assert (select count(*) from orders where userid = 10006) = 1000000, 'the extreme user must have 1000000 orders';
end $$;

\echo '== Volume data'
select count(*) as orders, pg_size_pretty(pg_relation_size('orders')) as table_size from orders;
select userid, count(*) as orders, count(*) filter (where status = 'NEW') as pending,
       count(*) filter (where status in ('FILLED', 'NEW')) as filled_and_pending
from orders where userid in (5000, 10005, 10006) group by userid order by userid;
