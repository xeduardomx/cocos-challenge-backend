package com.cocos.challenge.orders.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A page of orders. {@code nextCursor} is the value to send as {@code cursor} for the next one. */
public record OrderPageResponse(List<OrderResponse> orders, @Nullable String nextCursor) {}
