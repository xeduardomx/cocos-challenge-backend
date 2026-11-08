package com.cocos.challenge.orders.evaluation;

import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** A sized order waiting for a decision. The price is the one that would be recorded. */
public record OrderCandidate(
    long instrumentId,
    OrderSide side,
    OrderType type,
    int quantity,
    @Nullable BigDecimal price,
    boolean quoted) {

  public OrderCandidate {
    if (quoted && price == null) {
      throw new IllegalArgumentException("a quoted order needs the price it is evaluated at");
    }
  }
}
