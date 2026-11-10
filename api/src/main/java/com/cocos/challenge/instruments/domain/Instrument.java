package com.cocos.challenge.instruments.domain;

/**
 * An instrument of the catalog. The type is a text, not an enum, because the catalog can add types.
 */
public record Instrument(long id, String ticker, String name, String type) {

  public static final String CURRENCY_TYPE = "MONEDA";

  public boolean isCurrency() {
    return CURRENCY_TYPE.equals(type);
  }
}
