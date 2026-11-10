package com.cocos.challenge.orders.domain.submission;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** The quantity of an order and the price to record: null when a market order has no quote. */
public record ResolvedOrder(int quantity, @Nullable BigDecimal price) {}
