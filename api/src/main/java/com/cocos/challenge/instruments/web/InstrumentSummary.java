package com.cocos.challenge.instruments.web;

import com.cocos.challenge.instruments.Instrument;
import com.cocos.challenge.instruments.MarketPrice;
import com.cocos.challenge.instruments.search.SearchResult;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** An instrument in the search response. The price fields are null when there is no price. */
public record InstrumentSummary(
    long instrumentId,
    String ticker,
    String name,
    @Nullable BigDecimal lastPrice,
    @Nullable BigDecimal dailyChangePercent) {

  public static InstrumentSummary from(SearchResult result) {
    Instrument instrument = result.instrument();
    MarketPrice price = result.price();
    return new InstrumentSummary(
        instrument.id(),
        instrument.ticker(),
        instrument.name(),
        price == null ? null : price.close(),
        price == null ? null : price.dailyChangePercent());
  }
}
