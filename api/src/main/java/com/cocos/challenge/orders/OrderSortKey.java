package com.cocos.challenge.orders;

import java.time.OffsetDateTime;

/**
 * What the orders of a user are listed by, newest first: the time, then the id to break a tie. A
 * page starts after the key of the last order of the page before.
 */
public record OrderSortKey(OffsetDateTime createdAt, long orderId) {}
