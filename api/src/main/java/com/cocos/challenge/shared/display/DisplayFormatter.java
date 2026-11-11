package com.cocos.challenge.shared.display;

import com.cocos.challenge.shared.domain.Decimals;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

/** Shows dates in the display zone and money in the display locale. */
public class DisplayFormatter {

  private final ZoneId zone;
  private final Locale locale;

  public DisplayFormatter(DisplayProperties properties) {
    this.zone = properties.timeZone();
    this.locale = properties.locale();
  }

  public OffsetDateTime inDisplayZone(OffsetDateTime moment) {
    return moment.atZoneSameInstant(zone).toOffsetDateTime();
  }

  /**
   * No decimals for a whole amount, two otherwise: 500 is "500", 9258.5 is "9.258,50". The amount
   * is rounded once, before formatting, so the format never has to round.
   */
  public String formatMoney(BigDecimal amount) {
    BigDecimal rounded = Decimals.roundMoney(amount);
    int decimals = rounded.stripTrailingZeros().scale() <= 0 ? 0 : Decimals.MONEY_SCALE;
    // NumberFormat is not thread-safe, so each call gets its own
    NumberFormat format = NumberFormat.getNumberInstance(locale);
    format.setMinimumFractionDigits(decimals);
    format.setMaximumFractionDigits(decimals);
    return format.format(rounded);
  }

  /** A quantity is not money: grouping separators of the locale and no decimals. */
  public String formatQuantity(long quantity) {
    return NumberFormat.getIntegerInstance(locale).format(quantity);
  }
}
