package com.cocos.challenge.orders.error;

import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;

/** The request is well formed, but it cannot become an order. */
public class InvalidOrderException extends ApiException {

  public InvalidOrderException(String detailKey, String... detailArgs) {
    super(ProblemType.INVALID_ORDER, detailKey, detailArgs);
  }
}
