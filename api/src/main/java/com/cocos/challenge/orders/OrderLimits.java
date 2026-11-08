package com.cocos.challenge.orders;

/** The largest values an order can hold: what fits Java and the columns of {@code orders}. */
public final class OrderLimits {

  /** What fits the {@code INT} column of the size. */
  public static final int MAX_QUANTITY = Integer.MAX_VALUE;

  /** What fits {@code NUMERIC(10,2)}. A text because {@code @DecimalMax} takes one. */
  public static final String MAX_PRICE = "99999999.99";

  /**
   * Eighteen integer digits. No valid amount gets there: the largest one that still gives a
   * quantity that fits is {@code MAX_QUANTITY * MAX_PRICE}, about 2.1e17. Above it the quantity
   * never fits, so the limit cuts the request before the quantity is calculated.
   */
  public static final String MAX_AMOUNT = "999999999999999999.99";

  private OrderLimits() {}
}
