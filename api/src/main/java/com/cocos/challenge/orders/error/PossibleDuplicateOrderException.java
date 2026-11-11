package com.cocos.challenge.orders.error;

import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;
import java.util.Map;

/**
 * The user has an equal order, accepted moments ago and sent with another client order id: this one
 * may be a repetition nobody asked for (RFC 5.5).
 */
public class PossibleDuplicateOrderException extends ApiException {

  private static final String DETAIL_KEY = "order.possible-duplicate";
  private static final String EXISTING_ORDER_ID = "existingOrderId";

  private final long existingOrderId;

  public PossibleDuplicateOrderException(long existingOrderId) {
    super(ProblemType.POSSIBLE_DUPLICATE_ORDER, DETAIL_KEY, String.valueOf(existingOrderId));
    this.existingOrderId = existingOrderId;
  }

  @Override
  public Map<String, Object> properties() {
    return Map.of(EXISTING_ORDER_ID, existingOrderId);
  }
}
