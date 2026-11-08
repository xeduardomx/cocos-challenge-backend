-- Rules that look at a single row (RFC 3.2).
-- The CHECK constraints are validated now, against the existing rows, so no NOT VALID trap is left behind.

alter table orders
  alter column instrumentid set not null,
  alter column userid set not null,
  alter column size set not null,
  alter column type set not null,
  alter column side set not null,
  alter column status set not null,
  add constraint orders_side_check check (side in ('BUY', 'SELL', 'CASH_IN', 'CASH_OUT')),
  add constraint orders_type_check check (type in ('MARKET', 'LIMIT')),
  add constraint orders_status_check check (status in ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')),
  add constraint orders_size_check check (size >= 1),
  add constraint orders_price_check check (price > 0),
  add constraint orders_price_null_check check (price is not null or (status = 'REJECTED' and type = 'MARKET')),
  add constraint orders_cash_price_check check (side not in ('CASH_IN', 'CASH_OUT') or price = 1);

alter table instruments
  alter column ticker set not null,
  alter column name set not null,
  alter column type set not null,
  add constraint instruments_ticker_key unique (ticker);

alter table marketdata
  alter column instrumentid set not null,
  alter column date set not null,
  alter column close set not null,
  -- The unique constraint creates the index used to look up the latest price of each stock.
  add constraint marketdata_instrument_date_key unique (instrumentid, date),
  add constraint marketdata_prices_check
    check (open > 0 and high > 0 and low > 0 and close > 0 and previousclose > 0),
  add constraint marketdata_range_check
    check (low <= high and close between low and high and open between low and high);
