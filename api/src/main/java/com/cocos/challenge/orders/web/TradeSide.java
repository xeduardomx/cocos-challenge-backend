package com.cocos.challenge.orders.web;

import com.cocos.challenge.shared.domain.OrderSide;

/** The sides an order request can take: deposits and withdrawals are not sent through the API. */
public enum TradeSide {
  BUY(OrderSide.BUY),
  SELL(OrderSide.SELL);

  private final OrderSide orderSide;

  TradeSide(OrderSide orderSide) {
    this.orderSide = orderSide;
  }

  public OrderSide toOrderSide() {
    return orderSide;
  }
}
