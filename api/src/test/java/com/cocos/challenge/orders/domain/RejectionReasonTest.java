package com.cocos.challenge.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RejectionReasonTest {

  @Test
  void usesTheMessageKeyOfEachReason() {
    assertThat(RejectionReason.INSUFFICIENT_CASH.messageKey())
        .isEqualTo("order.rejection.insufficient-cash");
    assertThat(RejectionReason.INSUFFICIENT_SHARES.messageKey())
        .isEqualTo("order.rejection.insufficient-shares");
    assertThat(RejectionReason.NO_MARKET_PRICE.messageKey())
        .isEqualTo("order.rejection.no-market-price");
  }
}
