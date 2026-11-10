package com.cocos.challenge.orders.domain.evaluation;

import com.cocos.challenge.account.domain.Account;
import com.cocos.challenge.orders.domain.RejectionReason;
import java.util.Optional;

/** One reason to reject an order. Rules have no state. */
@FunctionalInterface
public interface RejectionRule {

  Optional<RejectionReason> check(OrderCandidate candidate, Account account);
}
