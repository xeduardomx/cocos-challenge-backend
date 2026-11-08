package com.cocos.challenge.shared.display;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DisplayFormatterTest {

  private final DisplayFormatter formatter =
      new DisplayFormatter(
          new DisplayProperties(
              ZoneId.of("America/Argentina/Buenos_Aires"), Locale.forLanguageTag("es-AR")));

  @Test
  void showsAWholeAmountWithoutDecimals() {
    assertThat(formatter.formatMoney(new BigDecimal("500"))).isEqualTo("500");
    assertThat(formatter.formatMoney(new BigDecimal("10000"))).isEqualTo("10.000");
    assertThat(formatter.formatMoney(new BigDecimal("500.00"))).isEqualTo("500");
  }

  @Test
  void showsTwoDecimalsWhenTheAmountHasThem() {
    assertThat(formatter.formatMoney(new BigDecimal("925.85"))).isEqualTo("925,85");
    assertThat(formatter.formatMoney(new BigDecimal("9258.50"))).isEqualTo("9.258,50");
  }

  @Test
  void roundsHalfUpToTwoDecimals() {
    assertThat(formatter.formatMoney(new BigDecimal("0.005"))).isEqualTo("0,01");
  }

  @Test
  void movesAMomentToTheDisplayZone() {
    OffsetDateTime utc = OffsetDateTime.parse("2023-07-12T15:31:20Z");

    assertThat(formatter.inDisplayZone(utc))
        .isEqualTo(OffsetDateTime.parse("2023-07-12T12:31:20-03:00"));
    assertThat(formatter.inDisplayZone(utc).getOffset().getId()).isEqualTo("-03:00");
  }
}
