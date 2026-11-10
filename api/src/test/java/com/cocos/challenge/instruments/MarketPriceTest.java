package com.cocos.challenge.instruments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketPriceTest {

  @Test
  void calculatesTheDailyChangeFromThePreviousClose() {
    MarketPrice price = new MarketPrice(47, new BigDecimal("925.85"), new BigDecimal("921.80"));

    assertThat(price.dailyChangePercent()).isEqualByComparingTo("0.44");
  }

  @Test
  void hasNoDailyChangeWithoutAPreviousClose() {
    MarketPrice price = new MarketPrice(47, new BigDecimal("925.85"), null);

    assertThat(price.dailyChangePercent()).isNull();
  }
}
