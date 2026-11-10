package com.cocos.challenge.orders.cancellation;

import com.cocos.challenge.account.AccountService;
import com.cocos.challenge.orders.OrderNotFoundException;
import com.cocos.challenge.orders.OrderRepository;
import com.cocos.challenge.orders.StoredOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a pending order, without the turn of the user (RFC 5.4): cancelling only releases
 * reservations, and it locks the row of the order, not the row of the user.
 */
@Service
public class OrderCancellationService {

  private final AccountService accounts;
  private final OrderRepository orders;

  public OrderCancellationService(AccountService accounts, OrderRepository orders) {
    this.accounts = accounts;
    this.orders = orders;
  }

  @Transactional
  public StoredOrder cancel(long userId, long orderId) {
    accounts.ensureExists(userId);
    return orders.cancelIfNew(userId, orderId).orElseGet(() -> explainWhyNot(userId, orderId));
  }

  /** Nothing changed: the order does not exist (or is not the user's), or it is not NEW. */
  private StoredOrder explainWhyNot(long userId, long orderId) {
    StoredOrder order =
        orders.findById(userId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    throw new OrderNotCancellableException(orderId, order.status());
  }
}
