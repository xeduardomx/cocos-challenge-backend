package com.cocos.challenge.orders;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An order as the database holds it. The identifier and the fingerprint are null in the orders that
 * existed before the API; the price is null in a market order that was rejected for lack of a
 * quote.
 */
public record StoredOrder(
    long id,
    long userId,
    long instrumentId,
    String ticker,
    OrderSide side,
    OrderType type,
    int size,
    @Nullable BigDecimal price,
    OrderStatus status,
    @Nullable UUID clientOrderId,
    @Nullable String requestFingerprint,
    @Nullable RejectionReason rejectionReason,
    OffsetDateTime createdAt) {}
