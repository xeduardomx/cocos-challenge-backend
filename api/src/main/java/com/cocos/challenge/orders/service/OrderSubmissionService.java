package com.cocos.challenge.orders.service;

import com.cocos.challenge.orders.domain.submission.SubmissionResult;
import com.cocos.challenge.orders.domain.submission.SubmitOrderCommand;
import com.cocos.challenge.orders.observability.OrderEventLogger;
import com.cocos.challenge.orders.observability.OrderMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Submits an order, with no transaction of its own (RFC 5.3). With the turn, two requests with the
 * same client order id never reach the uniqueness rule: the second one waits and finds the order of
 * the first. Any other insert of an order of the user waits for the turn too, because its foreign
 * key check takes a key share lock on the row of the user. The retry is the safety net for a path
 * without the turn: when the uniqueness rule stops an insert, its transaction is already rolled
 * back, so it starts again once and finds the order of the other. An order is counted and logged
 * here, once its transaction is confirmed.
 */
@Service
public class OrderSubmissionService {

  private final OrderSubmissionTransaction transaction;
  private final OrderMetrics metrics;
  private final OrderEventLogger eventLogger;

  public OrderSubmissionService(
      OrderSubmissionTransaction transaction, OrderMetrics metrics, OrderEventLogger eventLogger) {
    this.transaction = transaction;
    this.metrics = metrics;
    this.eventLogger = eventLogger;
  }

  public SubmissionResult submit(SubmitOrderCommand command) {
    SubmissionResult result = attempt(command);
    if (result.replayed()) {
      metrics.recordReplayed();
    } else {
      metrics.recordCreated(result.order());
    }
    eventLogger.logProcessed(result.order(), result.replayed());
    return result;
  }

  private SubmissionResult attempt(SubmitOrderCommand command) {
    try {
      return transaction.submit(command);
    } catch (DuplicateKeyException e) {
      return transaction.submit(command);
    }
  }
}
