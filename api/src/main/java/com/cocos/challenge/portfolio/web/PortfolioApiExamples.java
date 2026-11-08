package com.cocos.challenge.portfolio.web;

/** JSON examples of the portfolio endpoint, with the numbers of user 1. */
final class PortfolioApiExamples {

  static final String PORTFOLIO =
      """
      {
        "userId": 1,
        "totalValue": 889756.00,
        "cash": { "balance": 753000.00, "reserved": 125500.00, "available": 627500.00 },
        "positions": [
          { "instrumentId": 31, "ticker": "BMA", "name": "Banco Macro S.A.",
            "quantity": -10, "reservedQuantity": 0, "lastPrice": 1502.80, "marketValue": -15028.00,
            "averagePrice": null, "totalReturnPercent": null, "dailyChangePercent": -1.15 },
          { "instrumentId": 54, "ticker": "METR", "name": "MetroGAS S.A.",
            "quantity": 500, "reservedQuantity": 0, "lastPrice": 229.50, "marketValue": 114750.00,
            "averagePrice": 250.00, "totalReturnPercent": -8.20, "dailyChangePercent": -1.08 },
          { "instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.",
            "quantity": 40, "reservedQuantity": 0, "lastPrice": 925.85, "marketValue": 37034.00,
            "averagePrice": 930.00, "totalReturnPercent": -0.45, "dailyChangePercent": 0.44 }
        ]
      }\
      """;

  private PortfolioApiExamples() {}
}
