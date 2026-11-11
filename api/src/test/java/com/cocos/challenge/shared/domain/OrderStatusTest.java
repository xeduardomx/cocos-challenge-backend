package com.cocos.challenge.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

  @Test
  void anExecutedOrAPendingOrderIsAccepted() {
    assertThat(OrderStatus.FILLED.accepted()).isTrue();
    assertThat(OrderStatus.NEW.accepted()).isTrue();
  }

  @Test
  void aRejectedOrACancelledOrderIsNotAccepted() {
    assertThat(OrderStatus.REJECTED.accepted()).isFalse();
    assertThat(OrderStatus.CANCELLED.accepted()).isFalse();
  }
}
