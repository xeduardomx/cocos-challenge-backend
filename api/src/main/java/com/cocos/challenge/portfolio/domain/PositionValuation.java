package com.cocos.challenge.portfolio.domain;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A position at its latest price. The price fields are null when the instrument has no price. The
 * average price and the return are null when the position has no average price (a short one).
 */
public record PositionValuation(
    long instrumentId,
    String ticker,
    String name,
    long quantity,
    long reservedQuantity,
    @Nullable BigDecimal lastPrice,
    @Nullable BigDecimal marketValue,
    @Nullable BigDecimal averagePrice,
    @Nullable BigDecimal totalReturnPercent,
    @Nullable BigDecimal dailyChangePercent) {}
