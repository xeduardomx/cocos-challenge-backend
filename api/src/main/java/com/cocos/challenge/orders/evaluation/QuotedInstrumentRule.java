package com.cocos.challenge.orders.evaluation;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.orders.RejectionReason;
import java.util.Optional;

/** An instrument that does not quote cannot be traded, at market or with a limit. */
public class QuotedInstrumentRule implements RejectionRule {

  @Override
  public Optional<RejectionReason> check(OrderCandidate candidate, Account account) {
    return candidate.quoted() ? Optional.empty() : Optional.of(RejectionReason.NO_MARKET_PRICE);
  }
}
