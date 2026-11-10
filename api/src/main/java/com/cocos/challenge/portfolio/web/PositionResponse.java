package com.cocos.challenge.portfolio.web;

import com.cocos.challenge.portfolio.domain.PositionValuation;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** A position in the response. What is empty is shown as null, and the field is still there. */
public record PositionResponse(
    long instrumentId,
    String ticker,
    String name,
    long quantity,
    long reservedQuantity,
    @Nullable BigDecimal lastPrice,
    @Nullable BigDecimal marketValue,
    @Nullable BigDecimal averagePrice,
    @Nullable BigDecimal totalReturnPercent,
    @Nullable BigDecimal dailyChangePercent) {

  static PositionResponse from(PositionValuation valuation) {
    return new PositionResponse(
        valuation.instrumentId(),
        valuation.ticker(),
        valuation.name(),
        valuation.quantity(),
        valuation.reservedQuantity(),
        valuation.lastPrice(),
        valuation.marketValue(),
        valuation.averagePrice(),
        valuation.totalReturnPercent(),
        valuation.dailyChangePercent());
  }
}
