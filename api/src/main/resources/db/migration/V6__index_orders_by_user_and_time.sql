-- The only new index: portfolio, order list and cursor pagination read a user's orders by time.
create index orders_user_datetime_idx on orders (userid, datetime, id);
