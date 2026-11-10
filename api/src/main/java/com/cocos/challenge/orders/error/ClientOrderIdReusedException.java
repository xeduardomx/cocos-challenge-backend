package com.cocos.challenge.orders.error;

import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;

/** The client order id belongs to an order with other contents. */
public class ClientOrderIdReusedException extends ApiException {

  private static final String DETAIL_KEY = "order.client-order-id-reused";

  public ClientOrderIdReusedException() {
    super(ProblemType.CLIENT_ORDER_ID_REUSED, DETAIL_KEY);
  }
}
