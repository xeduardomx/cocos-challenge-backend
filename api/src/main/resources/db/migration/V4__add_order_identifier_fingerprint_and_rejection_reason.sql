-- New order columns, named like the Cocos ones: Postgres stores them in lower case (RFC 3.3).
alter table orders
  add column clientOrderId uuid,
  add column requestFingerprint varchar(64),
  add column rejectionReason varchar(30),
  add constraint orders_user_clientorderid_key unique (userid, clientOrderId),
  add constraint orders_fingerprint_check check ((clientOrderId is null) = (requestFingerprint is null)),
  add constraint orders_rejection_reason_check
    check (rejectionReason in ('INSUFFICIENT_CASH', 'INSUFFICIENT_SHARES', 'NO_MARKET_PRICE')),
  add constraint orders_rejection_only_rejected_check check (rejectionReason is null or status = 'REJECTED');
