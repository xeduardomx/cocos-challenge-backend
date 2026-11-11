package com.cocos.challenge.instruments.web;

/** JSON examples of the instrument search. */
final class InstrumentApiExamples {

  static final String SEARCH_RESULT =
      """
      {
        "instruments": [
          { "instrumentId": 47, "ticker": "PAMP", "name": "Pampa Holding S.A.", "lastPrice": 925.85, "dailyChangePercent": 0.44 }
        ]
      }\
      """;

  private InstrumentApiExamples() {}
}
