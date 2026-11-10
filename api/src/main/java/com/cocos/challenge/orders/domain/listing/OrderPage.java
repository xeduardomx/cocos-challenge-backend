package com.cocos.challenge.orders.domain.listing;

import com.cocos.challenge.orders.domain.StoredOrder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A page of orders; {@code nextCursor} is null on the last one. */
public record OrderPage(List<StoredOrder> orders, @Nullable String nextCursor) {}
