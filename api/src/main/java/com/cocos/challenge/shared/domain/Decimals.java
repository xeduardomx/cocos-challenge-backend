package com.cocos.challenge.shared.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Money rounding and division rules: divide with full precision, round once at the end. */
public final class Decimals {

  public static final int MONEY_SCALE = 2;
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
  public static final MathContext PRECISION = MathContext.DECIMAL128;
  public static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

  private Decimals() {}

  public static BigDecimal roundMoney(BigDecimal amount) {
    return amount.setScale(MONEY_SCALE, ROUNDING);
  }

  public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
    return dividend.divide(divisor, PRECISION);
  }

  /** The change from {@code base} to {@code value} as a percentage, rounded to money scale. */
  public static BigDecimal percentChange(BigDecimal base, BigDecimal value) {
    BigDecimal ratio = divide(value.subtract(base), base);
    return roundMoney(ratio.multiply(ONE_HUNDRED));
  }
}
