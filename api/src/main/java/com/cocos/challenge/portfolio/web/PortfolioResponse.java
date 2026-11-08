package com.cocos.challenge.portfolio.web;

import com.cocos.challenge.portfolio.Portfolio;
import java.math.BigDecimal;
import java.util.List;

/** The portfolio of a user (RFC 6.2). */
public record PortfolioResponse(
    long userId, BigDecimal totalValue, CashResponse cash, List<PositionResponse> positions) {

  public static PortfolioResponse from(long userId, Portfolio portfolio) {
    return new PortfolioResponse(
        userId,
        portfolio.totalValue(),
        new CashResponse(
            portfolio.cashBalance(), portfolio.reservedCash(), portfolio.availableCash()),
        portfolio.positions().stream().map(PositionResponse::from).toList());
  }
}
