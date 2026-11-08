package com.cocos.challenge.orders.submission;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Submits an order. It has no transaction of its own: when two requests with the same client order
 * id race and the uniqueness rule stops one of them, its transaction is already rolled back, so it
 * starts again once, as a retry, and finds the order of the other (RFC 5.3).
 */
@Service
public class OrderSubmissionService {

  private final OrderSubmissionTransaction transaction;

  public OrderSubmissionService(OrderSubmissionTransaction transaction) {
    this.transaction = transaction;
  }

  public SubmissionResult submit(SubmitOrderCommand command) {
    try {
      return transaction.submit(command);
    } catch (DuplicateKeyException e) {
      return transaction.submit(command);
    }
  }
}
