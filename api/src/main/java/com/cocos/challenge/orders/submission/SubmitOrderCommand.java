package com.cocos.challenge.orders.submission;

import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A validated order request, separated from its JSON. */
public record SubmitOrderCommand(
    long userId,
    UUID clientOrderId,
    long instrumentId,
    OrderSide side,
    OrderType type,
    @Nullable Integer quantity,
    @Nullable BigDecimal amount,
    @Nullable BigDecimal limitPrice) {}
