package com.cocos.challenge.orders.domain;

import java.util.Locale;

/** Why a well-formed order was recorded as rejected. */
public enum RejectionReason {
  INSUFFICIENT_CASH,
  INSUFFICIENT_SHARES,
  NO_MARKET_PRICE;

  private static final String MESSAGE_KEY_PREFIX = "order.rejection.";

  /** The key of the user-facing text, such as {@code order.rejection.insufficient-cash}. */
  public String messageKey() {
    return MESSAGE_KEY_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
