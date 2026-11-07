-- Cambios a la base de la sección 3 del RFC, tal como se probaron y midieron.
-- Es el borrador verificado: la migración definitiva se escribe en la fase 5, a partir de esta.
-- Se aplica sobre database.sql, en una copia local descartable (nunca en Neon).

create extension if not exists unaccent;

alter table orders
  -- Fecha y hora con zona: las fechas existentes se leen como hora argentina (D-10).
  -- La zona va escrita: un cast simple usaría la de la sesión, que en Neon es GMT.
  alter column datetime type timestamptz using datetime at time zone 'America/Argentina/Buenos_Aires',
  -- La hora la pone la base al grabar. now() no sirve: es la hora en que empezó la transacción.
  alter column datetime set default clock_timestamp(),
  alter column instrumentid set not null,
  alter column userid set not null,
  alter column size set not null,
  alter column type set not null,
  alter column side set not null,
  alter column status set not null,
  alter column datetime set not null,
  -- Columnas nuevas, con la convención de las de Cocos: Postgres las guarda en minúsculas.
  add column clientOrderId uuid,
  add column requestFingerprint varchar(64),
  add column rejectionReason varchar(30),
  add constraint orders_side_check check (side in ('BUY', 'SELL', 'CASH_IN', 'CASH_OUT')),
  add constraint orders_type_check check (type in ('MARKET', 'LIMIT')),
  add constraint orders_status_check check (status in ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')),
  add constraint orders_size_check check (size >= 1),
  add constraint orders_price_check check (price > 0),
  add constraint orders_price_null_check check (price is not null or (status = 'REJECTED' and type = 'MARKET')),
  add constraint orders_cash_price_check check (side not in ('CASH_IN', 'CASH_OUT') or price = 1),
  add constraint orders_user_clientorderid_key unique (userid, clientOrderId),
  add constraint orders_fingerprint_check check ((clientOrderId is null) = (requestFingerprint is null)),
  add constraint orders_rejection_reason_check
    check (rejectionReason in ('INSUFFICIENT_CASH', 'INSUFFICIENT_SHARES', 'NO_MARKET_PRICE')),
  add constraint orders_rejection_only_rejected_check check (rejectionReason is null or status = 'REJECTED');

-- El único índice nuevo: portfolio, listado y cursor (medido en 03-indices.sql).
create index orders_user_datetime_idx on orders (userid, datetime, id);

alter table instruments
  alter column ticker set not null,
  alter column name set not null,
  alter column type set not null,
  add constraint instruments_ticker_key unique (ticker);

alter table marketdata
  alter column instrumentid set not null,
  alter column date set not null,
  alter column close set not null,
  -- La regla de unicidad crea el índice con el que se busca el último precio de cada acción.
  add constraint marketdata_instrument_date_key unique (instrumentid, date),
  add constraint marketdata_prices_check
    check (open > 0 and high > 0 and low > 0 and close > 0 and previousclose > 0),
  add constraint marketdata_range_check
    check (low <= high and close between low and high and open between low and high);
