package com.cocos.challenge.shared.display;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

/** Shows dates in the display zone and money in the display locale. */
public class DisplayFormatter {

  private static final int MONEY_SCALE = 2;

  private final ZoneId zone;
  private final Locale locale;

  public DisplayFormatter(DisplayProperties properties) {
    this.zone = properties.timeZone();
    this.locale = properties.locale();
  }

  public OffsetDateTime inDisplayZone(OffsetDateTime moment) {
    return moment.atZoneSameInstant(zone).toOffsetDateTime();
  }

  /** No decimals for a whole amount, two otherwise: 500 is "500", 9258.5 is "9.258,50". */
  public String formatMoney(BigDecimal amount) {
    BigDecimal rounded = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    boolean whole = rounded.stripTrailingZeros().scale() <= 0;
    // NumberFormat is not thread-safe, so each call gets its own
    NumberFormat format = NumberFormat.getNumberInstance(locale);
    format.setRoundingMode(RoundingMode.HALF_UP);
    format.setMinimumFractionDigits(whole ? 0 : MONEY_SCALE);
    format.setMaximumFractionDigits(whole ? 0 : MONEY_SCALE);
    return format.format(rounded);
  }

  /** A quantity is not money: grouping separators of the locale and no decimals. */
  public String formatQuantity(long quantity) {
    return NumberFormat.getIntegerInstance(locale).format(quantity);
  }
}
