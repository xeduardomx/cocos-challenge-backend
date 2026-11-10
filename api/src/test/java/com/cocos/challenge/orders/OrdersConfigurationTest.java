package com.cocos.challenge.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.orders.evaluation.OrderCandidate;
import com.cocos.challenge.orders.evaluation.OrderDecision;
import com.cocos.challenge.orders.evaluation.OrderEvaluator;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrdersConfigurationTest {

  private static final long PGR = 3;

  private final OrdersConfiguration configuration = new OrdersConfiguration();

  @Test
  void checksTheQuoteBeforeTheCash() {
    OrderEvaluator evaluator =
        configuration.orderEvaluator(
            configuration.quotedInstrumentRule(),
            configuration.enoughCashRule(),
            configuration.enoughSharesRule());
    Account empty = new Account(new BigDecimal("0.00"), new BigDecimal("0.00"), Map.of());
    BigDecimal price = new BigDecimal("10.00");

    OrderCandidate unquotedPurchase =
        new OrderCandidate(PGR, OrderSide.BUY, OrderType.LIMIT, 1, price, false);
    OrderCandidate quotedPurchase =
        new OrderCandidate(PGR, OrderSide.BUY, OrderType.LIMIT, 1, price, true);

    OrderDecision unquoted = evaluator.evaluate(unquotedPurchase, empty);
    OrderDecision quoted = evaluator.evaluate(quotedPurchase, empty);

    assertThat(unquoted.rejectionReason()).isEqualTo(RejectionReason.NO_MARKET_PRICE);
    assertThat(quoted.rejectionReason()).isEqualTo(RejectionReason.INSUFFICIENT_CASH);
  }
}
