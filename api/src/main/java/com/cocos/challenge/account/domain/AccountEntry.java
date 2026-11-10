package com.cocos.challenge.account.domain;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;

/** The part of an order the account calculation needs. */
public record AccountEntry(
    long instrumentId, OrderSide side, OrderStatus status, int size, BigDecimal price) {

  BigDecimal amount() {
    return price.multiply(BigDecimal.valueOf(size));
  }
}
