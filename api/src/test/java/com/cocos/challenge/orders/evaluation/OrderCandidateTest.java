package com.cocos.challenge.orders.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderCandidateTest {

  private static final long PAMP = 47;

  @Test
  void rejectsAQuotedCandidateWithoutPrice() {
    assertThatThrownBy(
            () -> new OrderCandidate(PAMP, OrderSide.BUY, OrderType.MARKET, 10, null, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsAnUnquotedCandidateWithoutPrice() {
    OrderCandidate candidate =
        new OrderCandidate(PAMP, OrderSide.BUY, OrderType.MARKET, 10, null, false);

    assertThat(candidate.price()).isNull();
  }

  @Test
  void acceptsAQuotedCandidateWithPrice() {
    OrderCandidate candidate =
        new OrderCandidate(
            PAMP, OrderSide.BUY, OrderType.MARKET, 10, new BigDecimal("925.85"), true);

    assertThat(candidate.quoted()).isTrue();
  }
}
