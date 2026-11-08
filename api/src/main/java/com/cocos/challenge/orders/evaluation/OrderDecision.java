package com.cocos.challenge.orders.evaluation;

import com.cocos.challenge.orders.RejectionReason;
import com.cocos.challenge.shared.domain.OrderStatus;
import org.jspecify.annotations.Nullable;

/** The status an order gets and, when it is rejected, why. */
public record OrderDecision(OrderStatus status, @Nullable RejectionReason rejectionReason) {}
