package com.cocos.challenge.instruments;

import com.cocos.challenge.shared.domain.Decimals;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** The latest close of an instrument and the close before it, when there is one. */
public record MarketPrice(long instrumentId, BigDecimal close, @Nullable BigDecimal previousClose) {

  /** The daily change in percent, or null when there is no previous close. */
  public @Nullable BigDecimal dailyChangePercent() {
    return previousClose == null ? null : Decimals.percentChange(previousClose, close);
  }
}
