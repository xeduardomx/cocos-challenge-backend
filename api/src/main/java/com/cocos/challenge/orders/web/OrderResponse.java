package com.cocos.challenge.orders.web;

import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An order in the API. {@code amount} is the pesos of the order: quantity times price, or what a
 * deposit or a withdrawal moves, which have no quantity or price.
 */
public record OrderResponse(
    long id,
    @Nullable UUID clientOrderId,
    long instrumentId,
    String ticker,
    OrderSide side,
    OrderType type,
    @Nullable Integer quantity,
    @Nullable BigDecimal price,
    @Nullable BigDecimal amount,
    OrderStatus status,
    @Nullable RejectionReason rejectionReason,
    @Nullable String rejectionMessage,
    OffsetDateTime datetime) {}
