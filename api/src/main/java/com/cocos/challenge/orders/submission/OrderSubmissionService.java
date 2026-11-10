package com.cocos.challenge.orders.submission;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Submits an order, with no transaction of its own (RFC 5.3). With the turn, two requests with the
 * same client order id never reach the uniqueness rule: the second one waits and finds the order of
 * the first. Any other insert of an order of the user waits for the turn too, because its foreign
 * key check takes a key share lock on the row of the user. The retry is the safety net for a path
 * without the turn: when the uniqueness rule stops an insert, its transaction is already rolled
 * back, so it starts again once and finds the order of the other.
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
