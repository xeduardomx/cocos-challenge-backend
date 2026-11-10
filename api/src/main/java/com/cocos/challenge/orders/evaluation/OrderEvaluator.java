package com.cocos.challenge.orders.evaluation;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.orders.RejectionReason;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.util.List;
import java.util.Optional;

/**
 * Decides the status of an order: the first rule that rejects wins. Without a rejection a market
 * order is filled and a limit order stays pending.
 */
public class OrderEvaluator {

  private final List<RejectionRule> rules;

  public OrderEvaluator(List<RejectionRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public OrderDecision evaluate(OrderCandidate candidate, Account account) {
    Optional<RejectionReason> rejection =
        rules.stream()
            .map(rule -> rule.check(candidate, account))
            .flatMap(Optional::stream)
            .findFirst();
    if (rejection.isPresent()) {
      return new OrderDecision(OrderStatus.REJECTED, rejection.get());
    }
    OrderStatus status =
        candidate.type() == OrderType.MARKET ? OrderStatus.FILLED : OrderStatus.NEW;
    return new OrderDecision(status, null);
  }
}
