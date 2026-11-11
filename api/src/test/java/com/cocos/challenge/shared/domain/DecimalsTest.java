package com.cocos.challenge.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DecimalsTest {

  @ParameterizedTest
  @CsvSource({"921.80, 925.85, 0.44", "1047.17, 1038.10, -0.87", "250, 229.50, -8.20"})
  void calculatesThePercentChangeRoundedToTwoDecimals(String base, String value, String expected) {
    BigDecimal change = Decimals.percentChange(new BigDecimal(base), new BigDecimal(value));

    assertThat(change).isEqualByComparingTo(expected);
    assertThat(change.scale()).isEqualTo(Decimals.MONEY_SCALE);
  }

  @Test
  void roundsMoneyHalfUp() {
    assertThat(Decimals.roundMoney(new BigDecimal("100.005"))).isEqualTo(new BigDecimal("100.01"));
  }

  @Test
  void dividesWithDecimal128Precision() {
    BigDecimal third = Decimals.divide(BigDecimal.ONE, new BigDecimal("3"));

    assertThat(third.precision()).isEqualTo(Decimals.PRECISION.getPrecision());
  }

  @Test
  void zeroMoneyHasTwoDecimals() {
    assertThat(Decimals.ZERO_MONEY).isEqualTo(new BigDecimal("0.00"));
  }
}
