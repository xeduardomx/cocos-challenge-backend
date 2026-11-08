package com.cocos.challenge.orders.evaluation;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.orders.RejectionReason;
import java.util.Optional;

/** One reason to reject an order. Rules have no state. */
@FunctionalInterface
public interface RejectionRule {

  Optional<RejectionReason> check(OrderCandidate candidate, Account account);
}
