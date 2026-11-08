package com.cocos.challenge.orders;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An order to record. Its id, its ticker and its time are given by the database. */
public record NewOrder(
    long userId,
    long instrumentId,
    OrderSide side,
    OrderType type,
    int size,
    @Nullable BigDecimal price,
    OrderStatus status,
    UUID clientOrderId,
    String requestFingerprint,
    @Nullable RejectionReason rejectionReason) {}
