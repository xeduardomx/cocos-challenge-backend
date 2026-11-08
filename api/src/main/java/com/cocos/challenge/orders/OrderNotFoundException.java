package com.cocos.challenge.orders;

import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;

/** The order does not exist, or belongs to another user: the answer is the same (CAN-4). */
public class OrderNotFoundException extends ApiException {

  private static final String DETAIL_KEY = "order.not-found";

  public OrderNotFoundException(long orderId) {
    super(ProblemType.ORDER_NOT_FOUND, DETAIL_KEY, String.valueOf(orderId));
  }
}
