package com.cocos.challenge.support;

/** The JSON of order submissions that several integration tests send. */
public final class OrderRequests {

  /** The instrument every shared request buys. */
  public static final long PAMP = 47;

  private OrderRequests() {}

  /**
   * A MARKET purchase of PAMP.
   *
   * @param sizing the quantity or the amount, as JSON members, for example {@code "quantity": 10}
   */
  public static String marketBuy(Object clientOrderId, String sizing) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "MARKET", %s }\
    """
        .formatted(clientOrderId, PAMP, sizing);
  }
}
