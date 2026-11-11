-- Order time with time zone. Existing values are read as Argentine time.
-- The zone is explicit: a plain cast would use the session zone, which is GMT on Neon.
-- The database stamps the time when the row is written: now() would give the start of the transaction.
alter table orders
  alter column datetime type timestamptz using datetime at time zone 'America/Argentina/Buenos_Aires',
  alter column datetime set default clock_timestamp(),
  alter column datetime set not null;
