package com.cocos.challenge.orders.service;

import com.cocos.challenge.account.service.AccountService;
import com.cocos.challenge.orders.domain.StoredOrder;
import com.cocos.challenge.orders.error.OrderNotCancellableException;
import com.cocos.challenge.orders.error.OrderNotFoundException;
import com.cocos.challenge.orders.observability.OrderEventLogger;
import com.cocos.challenge.orders.observability.OrderMetrics;
import com.cocos.challenge.orders.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cancels a pending order, without the turn of the user (RFC 5.4): cancelling only releases
 * reservations, and it locks the row of the order, not the row of the user.
 */
@Service
public class OrderCancellationService {

  private final AccountService accounts;
  private final OrderRepository orders;
  private final OrderMetrics metrics;
  private final OrderEventLogger eventLogger;

  public OrderCancellationService(
      AccountService accounts,
      OrderRepository orders,
      OrderMetrics metrics,
      OrderEventLogger eventLogger) {
    this.accounts = accounts;
    this.orders = orders;
    this.metrics = metrics;
    this.eventLogger = eventLogger;
  }

  @Transactional
  public StoredOrder cancel(long userId, long orderId) {
    accounts.ensureExists(userId);
    StoredOrder cancelled =
        orders.cancelIfNew(userId, orderId).orElseGet(() -> explainWhyNot(userId, orderId));
    recordAfterCommit(cancelled);
    return cancelled;
  }

  /** A cancellation is counted and logged only once its transaction is confirmed. */
  private void recordAfterCommit(StoredOrder cancelled) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            metrics.recordCancelled();
            eventLogger.logCancelled(cancelled);
          }
        });
  }

  /** Nothing changed: the order does not exist (or is not the user's), or it is not NEW. */
  private StoredOrder explainWhyNot(long userId, long orderId) {
    StoredOrder order =
        orders.findById(userId, orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    throw new OrderNotCancellableException(orderId, order.status());
  }
}
