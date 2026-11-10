package com.cocos.challenge.portfolio;

import java.math.BigDecimal;
import java.util.List;

/** The pesos of a user and its positions at the latest prices, sorted by ticker. */
public record Portfolio(
    BigDecimal totalValue,
    BigDecimal cashBalance,
    BigDecimal reservedCash,
    BigDecimal availableCash,
    List<PositionValuation> positions) {}
