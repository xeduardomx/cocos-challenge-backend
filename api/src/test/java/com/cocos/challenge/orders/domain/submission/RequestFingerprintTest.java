package com.cocos.challenge.orders.domain.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  private static final long PAMP = 47;
  private static final String TEN_THOUSAND_PESOS =
      "7fab93192961cf0c3f0999befdff587f21b61d9a73c2ac6b49dabb7688c1a727";
  private static final String TWENTY_THOUSAND_PESOS =
      "5877f5a9942d5ffea1337283f79fe0c3339a3b1a16faca05b50f56b741475a0c";

  private static SubmitOrderCommand byAmount(
      OrderSide side, OrderType type, String amount, @Nullable String limitPrice) {
    return new SubmitOrderCommand(
        1,
        UUID.randomUUID(),
        PAMP,
        side,
        type,
        null,
        new BigDecimal(amount),
        limitPrice == null ? null : new BigDecimal(limitPrice));
  }

  private static String of(SubmitOrderCommand command) {
    return RequestFingerprint.of(command).value();
  }

  @Test
  void hashesTheFixedShapeOfTheRequest() {
    assertThat(of(byAmount(OrderSide.BUY, OrderType.MARKET, "10000", null)))
        .isEqualTo(TEN_THOUSAND_PESOS);
    assertThat(of(byAmount(OrderSide.BUY, OrderType.MARKET, "20000", null)))
        .isEqualTo(TWENTY_THOUSAND_PESOS);
  }

  @Test
  void writesTheSameAmountInAnyFormTheSame() {
    assertThat(of(byAmount(OrderSide.BUY, OrderType.MARKET, "10000.00", null)))
        .isEqualTo(TEN_THOUSAND_PESOS);
    assertThat(of(byAmount(OrderSide.BUY, OrderType.MARKET, "1E+4", null)))
        .isEqualTo(TEN_THOUSAND_PESOS);
  }

  @Test
  void ignoresTheUserAndTheClientOrderId() {
    SubmitOrderCommand first = byAmount(OrderSide.BUY, OrderType.MARKET, "10000", null);
    SubmitOrderCommand other =
        new SubmitOrderCommand(
            2,
            UUID.randomUUID(),
            first.instrumentId(),
            first.side(),
            first.type(),
            first.quantity(),
            first.amount(),
            first.limitPrice());

    assertThat(of(other)).isEqualTo(of(first));
  }

  @Test
  void changesWithTheLimitPrice() {
    assertThat(of(byAmount(OrderSide.BUY, OrderType.LIMIT, "10000", "900")))
        .isNotEqualTo(of(byAmount(OrderSide.BUY, OrderType.LIMIT, "10000", "901")))
        .isNotEqualTo(of(byAmount(OrderSide.BUY, OrderType.MARKET, "10000", null)));
  }

  @Test
  void changesWithTheSide() {
    assertThat(of(byAmount(OrderSide.SELL, OrderType.MARKET, "10000", null)))
        .isNotEqualTo(TEN_THOUSAND_PESOS);
  }

  @Test
  void tellsAQuantityFromAnAmountWithTheSameNumber() {
    SubmitOrderCommand byQuantity =
        new SubmitOrderCommand(
            1, UUID.randomUUID(), PAMP, OrderSide.BUY, OrderType.MARKET, 10, null, null);
    SubmitOrderCommand byTen = byAmount(OrderSide.BUY, OrderType.MARKET, "10", null);

    assertThat(of(byQuantity)).isNotEqualTo(of(byTen));
  }
}
