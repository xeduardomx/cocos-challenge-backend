package com.cocos.challenge.shared.domain;

/** The state of an order. */
public enum OrderStatus {
  NEW,
  FILLED,
  REJECTED,
  CANCELLED;

  /**
   * Whether an order in this state takes effect on the account of its user: it was executed, or it
   * is pending and reserves cash or shares.
   */
  public boolean accepted() {
    return this == FILLED || this == NEW;
  }
}
