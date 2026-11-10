package com.cocos.challenge.orders.evaluation;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.orders.RejectionReason;
import com.cocos.challenge.shared.domain.OrderSide;
import java.util.Optional;

/** A sale needs the available shares of the instrument to cover the quantity. */
public class EnoughSharesRule implements RejectionRule {

  @Override
  public Optional<RejectionReason> check(OrderCandidate candidate, Account account) {
    if (candidate.side() != OrderSide.SELL) {
      return Optional.empty();
    }
    return candidate.quantity() > account.availableShares(candidate.instrumentId())
        ? Optional.of(RejectionReason.INSUFFICIENT_SHARES)
        : Optional.empty();
  }
}
