package com.cocos.challenge.orders.web;

import com.cocos.challenge.orders.OrderLimits;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.orders.submission.SubmitOrderCommand;
import com.cocos.challenge.shared.domain.Decimals;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.validation.MaxScale;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The body of an order request. The money limits use {@code @DecimalMax}, which compares numbers,
 * and not {@code @Digits}, which overflows with a huge exponent such as {@code 1e2147483647}.
 */
@ConsistentOrderRequest
public record SubmitOrderRequest(
    @NotNull(message = "{order.client-order-id-missing}") UUID clientOrderId,
    @NotNull(message = "{order.instrument-id-missing}") Long instrumentId,
    @NotNull(message = "{order.side-missing}") OrderSide side,
    @NotNull(message = "{order.type-missing}") OrderType type,
    @Positive(message = "{order.quantity-not-positive}") @Nullable Integer quantity,
    @Positive(message = "{order.amount-not-positive}")
        @MaxScale(value = Decimals.MONEY_SCALE, message = "{order.amount-too-many-decimals}")
        @DecimalMax(value = OrderLimits.MAX_AMOUNT, message = "{order.amount-too-large}")
        @Nullable BigDecimal amount,
    @Positive(message = "{order.price-not-positive}")
        @MaxScale(value = Decimals.MONEY_SCALE, message = "{order.price-too-many-decimals}")
        @DecimalMax(value = OrderLimits.MAX_PRICE, message = "{order.price-too-large}")
        @Nullable BigDecimal price) {

  public SubmitOrderCommand toCommand(long userId) {
    return new SubmitOrderCommand(
        userId, clientOrderId, instrumentId, side, type, quantity, amount, price);
  }
}
