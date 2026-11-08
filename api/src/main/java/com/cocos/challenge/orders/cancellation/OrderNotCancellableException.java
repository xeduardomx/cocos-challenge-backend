package com.cocos.challenge.orders.cancellation;

import com.cocos.challenge.shared.domain.OrderStatus;
import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;
import java.util.Map;

/** The order is no longer {@code NEW}, so there is nothing to cancel (CAN-3). */
public class OrderNotCancellableException extends ApiException {

  private static final String DETAIL_KEY = "order.not-cancellable";
  private static final String CURRENT_STATUS = "currentStatus";

  private final OrderStatus currentStatus;

  public OrderNotCancellableException(long orderId, OrderStatus currentStatus) {
    super(
        ProblemType.ORDER_NOT_CANCELLABLE,
        DETAIL_KEY,
        String.valueOf(orderId),
        currentStatus.name());
    this.currentStatus = currentStatus;
  }

  @Override
  public Map<String, Object> properties() {
    return Map.of(CURRENT_STATUS, currentStatus.name());
  }
}
