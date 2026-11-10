package com.cocos.challenge.orders.domain.evaluation;

import com.cocos.challenge.account.domain.Account;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import java.util.Optional;

/** A purchase needs the available pesos to cover quantity times price. */
public class EnoughCashRule implements RejectionRule {

  @Override
  public Optional<RejectionReason> check(OrderCandidate candidate, Account account) {
    BigDecimal price = candidate.price();
    if (candidate.side() != OrderSide.BUY || price == null) {
      return Optional.empty();
    }
    BigDecimal cost = price.multiply(BigDecimal.valueOf(candidate.quantity()));
    return cost.compareTo(account.availableCash()) > 0
        ? Optional.of(RejectionReason.INSUFFICIENT_CASH)
        : Optional.empty();
  }
}
