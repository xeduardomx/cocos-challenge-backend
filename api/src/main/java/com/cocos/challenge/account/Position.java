package com.cocos.challenge.account;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * The shares a user holds of one instrument. Quantities are {@code long}: each order fits in an
 * {@code int}, but the sum of several may not. The average price is exact, not rounded, and is
 * empty while the quantity is zero or negative.
 */
public record Position(
    long instrumentId, long quantity, long reservedQuantity, @Nullable BigDecimal averagePrice) {

  public long availableQuantity() {
    return quantity - reservedQuantity;
  }
}
