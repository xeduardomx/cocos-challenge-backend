package com.cocos.challenge.orders.web;

/** JSON examples of the order endpoints, with the numbers of user 1. */
final class OrderApiExamples {

  static final String MARKET_BUY_REQUEST =
      """
      { "clientOrderId": "3f2b8c1e-9d4a-4c7b-8e21-5a6f0b9c2d13", "instrumentId": 47, "side": "BUY", "type": "MARKET", "quantity": 10 }\
      """;

  static final String LIMIT_BUY_BY_AMOUNT_REQUEST =
      """
      { "clientOrderId": "7c1d5e9a-2b3f-4a60-9d8e-1f2a3b4c5d6e", "instrumentId": 47, "side": "BUY", "type": "LIMIT", "amount": 10000.00, "price": 900.00 }\
      """;

  static final String FILLED_ORDER =
      """
      {
        "id": 12,
        "clientOrderId": "3f2b8c1e-9d4a-4c7b-8e21-5a6f0b9c2d13",
        "instrumentId": 47,
        "ticker": "PAMP",
        "side": "BUY",
        "type": "MARKET",
        "quantity": 10,
        "price": 925.85,
        "amount": 9258.50,
        "status": "FILLED",
        "rejectionReason": null,
        "rejectionMessage": null,
        "datetime": "2026-11-08T11:02:15.483921-03:00"
      }\
      """;

  static final String REJECTED_ORDER =
      """
      {
        "id": 12, "clientOrderId": "9b4e2f71-0c3d-4a8b-b5e6-7f1a2c3d4e5f", "instrumentId": 47, "ticker": "PAMP",
        "side": "BUY", "type": "MARKET", "quantity": 756, "price": 925.85, "amount": 699942.60,
        "status": "REJECTED", "rejectionReason": "INSUFFICIENT_CASH",
        "rejectionMessage": "No alcanzan los pesos disponibles para esta orden.",
        "datetime": "2026-11-08T11:03:40.120734-03:00"
      }\
      """;

  static final String CANCELLED_ORDER =
      """
      {
        "id": 7, "clientOrderId": null, "instrumentId": 31, "ticker": "BMA", "side": "BUY", "type": "LIMIT",
        "quantity": 60, "price": 1500.00, "amount": 90000.00, "status": "CANCELLED",
        "rejectionReason": null, "rejectionMessage": null, "datetime": "2023-07-13T11:13:20-03:00"
      }\
      """;

  static final String PENDING_ORDERS =
      """
      {
        "orders": [
          { "id": 7, "clientOrderId": null, "instrumentId": 31, "ticker": "BMA", "side": "BUY", "type": "LIMIT",
            "quantity": 60, "price": 1500.00, "amount": 90000.00, "status": "NEW",
            "rejectionReason": null, "rejectionMessage": null, "datetime": "2023-07-13T11:13:20-03:00" },
          { "id": 5, "clientOrderId": null, "instrumentId": 45, "ticker": "LOMA", "side": "BUY", "type": "LIMIT",
            "quantity": 50, "price": 710.00, "amount": 35500.00, "status": "NEW",
            "rejectionReason": null, "rejectionMessage": null, "datetime": "2023-07-12T15:14:20-03:00" }
        ],
        "nextCursor": null
      }\
      """;

  private OrderApiExamples() {}
}
