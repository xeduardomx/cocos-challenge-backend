package com.cocos.challenge.shared.domain;

/** What an order does: trade a stock or move pesos in or out of the account. */
public enum OrderSide {
  BUY,
  SELL,
  CASH_IN,
  CASH_OUT;

  /** Whether the order trades a stock, as opposed to a deposit or a withdrawal. */
  public boolean isTrade() {
    return this == BUY || this == SELL;
  }
}
