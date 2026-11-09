package com.cocos.challenge.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class SubmitOrderRequestValidationTest {

  private static final long PAMP = 47;
  private static final Duration HUGE_NUMBER_TIMEOUT = Duration.ofSeconds(5);
  private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = FACTORY.getValidator();

  @AfterAll
  static void closeFactory() {
    FACTORY.close();
  }

  /**
   * Builds a request the way Jackson can: any field may be null. The request type does not allow
   * it, because {@code @NotNull} is what stops it, and that is what these tests check.
   */
  @SuppressWarnings("NullAway")
  private static SubmitOrderRequest requestOf(
      @Nullable UUID clientOrderId,
      @Nullable Long instrumentId,
      @Nullable TradeSide side,
      @Nullable OrderType type,
      @Nullable Integer quantity,
      @Nullable BigDecimal amount,
      @Nullable BigDecimal price) {
    return new SubmitOrderRequest(clientOrderId, instrumentId, side, type, quantity, amount, price);
  }

  private static SubmitOrderRequest request(
      @Nullable UUID clientOrderId,
      @Nullable TradeSide side,
      @Nullable OrderType type,
      @Nullable Integer quantity,
      @Nullable BigDecimal amount,
      @Nullable BigDecimal price) {
    return requestOf(clientOrderId, PAMP, side, type, quantity, amount, price);
  }

  private static SubmitOrderRequest marketBuyOf(
      @Nullable Integer quantity, @Nullable BigDecimal amount) {
    return request(UUID.randomUUID(), TradeSide.BUY, OrderType.MARKET, quantity, amount, null);
  }

  private static SubmitOrderRequest limitBuyOf(
      @Nullable Integer quantity, @Nullable BigDecimal price) {
    return request(UUID.randomUUID(), TradeSide.BUY, OrderType.LIMIT, quantity, null, price);
  }

  /** The violations as "field -> message template", so a test sees both at once. */
  private static Set<String> violationsOf(SubmitOrderRequest request) {
    return VALIDATOR.validate(request).stream()
        .map(SubmitOrderRequestValidationTest::describe)
        .collect(Collectors.toSet());
  }

  private static String describe(ConstraintViolation<SubmitOrderRequest> violation) {
    return violation.getPropertyPath() + " -> " + violation.getMessageTemplate();
  }

  @Test
  void acceptsAMarketOrderByQuantity() {
    assertThat(violationsOf(marketBuyOf(10, null))).isEmpty();
  }

  @Test
  void acceptsAMarketOrderByAmount() {
    assertThat(violationsOf(marketBuyOf(null, new BigDecimal("10000.50")))).isEmpty();
  }

  @Test
  void acceptsALimitOrderWithAPrice() {
    assertThat(violationsOf(limitBuyOf(10, new BigDecimal("900.5")))).isEmpty();
  }

  @Test
  void rejectsAMissingClientOrderId() {
    SubmitOrderRequest request = request(null, TradeSide.BUY, OrderType.MARKET, 10, null, null);

    assertThat(violationsOf(request))
        .containsExactly("clientOrderId -> {order.client-order-id-missing}");
  }

  @Test
  void rejectsAMissingInstrument() {
    SubmitOrderRequest request =
        requestOf(UUID.randomUUID(), null, TradeSide.BUY, OrderType.MARKET, 10, null, null);

    assertThat(violationsOf(request))
        .containsExactly("instrumentId -> {order.instrument-id-missing}");
  }

  @Test
  void reportsOnlyTheTwoMissingEnumsWhenSideAndTypeAreMissing() {
    SubmitOrderRequest request = request(UUID.randomUUID(), null, null, 10, null, null);

    assertThat(violationsOf(request))
        .containsExactlyInAnyOrder("side -> {order.side-missing}", "type -> {order.type-missing}");
  }

  @Test
  void rejectsAQuantityOfZero() {
    assertThat(violationsOf(marketBuyOf(0, null)))
        .containsExactly("quantity -> {order.quantity-not-positive}");
  }

  @Test
  void rejectsANegativeAmount() {
    assertThat(violationsOf(marketBuyOf(null, new BigDecimal("-1"))))
        .containsExactly("amount -> {order.amount-not-positive}");
  }

  @Test
  void rejectsAnAmountWithMoreThanTwoDecimals() {
    assertThat(violationsOf(marketBuyOf(null, new BigDecimal("100.555"))))
        .containsExactly("amount -> {order.amount-too-many-decimals}");
  }

  @Test
  void acceptsAnAmountWhoseExtraDecimalsAreZeros() {
    assertThat(violationsOf(marketBuyOf(null, new BigDecimal("100.500")))).isEmpty();
  }

  @Test
  void rejectsAPriceOfZero() {
    assertThat(violationsOf(limitBuyOf(10, BigDecimal.ZERO)))
        .containsExactly("price -> {order.price-not-positive}");
  }

  @Test
  void rejectsAPriceWithMoreThanTwoDecimals() {
    assertThat(violationsOf(limitBuyOf(10, new BigDecimal("229.555"))))
        .containsExactly("price -> {order.price-too-many-decimals}");
  }

  @Test
  void rejectsAPriceThatDoesNotFitTheColumn() {
    assertThat(violationsOf(limitBuyOf(10, new BigDecimal("100000000.00"))))
        .containsExactly("price -> {order.price-too-large}");
  }

  @Test
  void acceptsTheLargestPriceThatFitsTheColumn() {
    assertThat(violationsOf(limitBuyOf(10, new BigDecimal("99999999.99")))).isEmpty();
  }

  @Test
  void rejectsAnAmountOverTheLimit() {
    assertThat(violationsOf(marketBuyOf(null, new BigDecimal("1000000000000000000.00"))))
        .containsExactly("amount -> {order.amount-too-large}");
  }

  @Test
  void rejectsQuantityAndAmountTogether() {
    assertThat(violationsOf(marketBuyOf(10, new BigDecimal("1000"))))
        .containsExactly("quantity -> {order.quantity-and-amount}");
  }

  @Test
  void rejectsAnOrderWithNeitherQuantityNorAmount() {
    assertThat(violationsOf(marketBuyOf(null, null)))
        .containsExactly("quantity -> {order.quantity-or-amount-missing}");
  }

  @Test
  void rejectsALimitOrderWithoutPrice() {
    assertThat(violationsOf(limitBuyOf(10, null)))
        .containsExactly("price -> {order.limit-needs-price}");
  }

  @Test
  void rejectsAMarketOrderWithAPrice() {
    SubmitOrderRequest request =
        request(
            UUID.randomUUID(), TradeSide.BUY, OrderType.MARKET, 10, null, new BigDecimal("925.85"));

    assertThat(violationsOf(request)).containsExactly("price -> {order.market-has-price}");
  }

  @Test
  void reportsEveryInconsistencyOfTheSameOrder() {
    SubmitOrderRequest request =
        request(
            UUID.randomUUID(),
            TradeSide.BUY,
            OrderType.MARKET,
            null,
            null,
            new BigDecimal("925.85"));

    assertThat(violationsOf(request))
        .containsExactlyInAnyOrder(
            "quantity -> {order.quantity-or-amount-missing}", "price -> {order.market-has-price}");
  }

  @Test
  void rejectsAnAmountWithAHugeExponentWithoutBuildingTheNumber() {
    assertTimeoutPreemptively(
        HUGE_NUMBER_TIMEOUT,
        () ->
            assertThat(violationsOf(marketBuyOf(null, new BigDecimal("1e999999999"))))
                .containsExactly("amount -> {order.amount-too-large}"));
  }

  @Test
  void rejectsAPriceWithAHugeExponentWithoutBuildingTheNumber() {
    assertTimeoutPreemptively(
        HUGE_NUMBER_TIMEOUT,
        () ->
            assertThat(violationsOf(limitBuyOf(10, new BigDecimal("1e2147483647"))))
                .containsExactly("price -> {order.price-too-large}"));
  }

  @Test
  void rejectsAHugeExponentWithZerosInTheMantissaWithoutFailing() {
    assertThat(violationsOf(marketBuyOf(null, new BigDecimal("100e2147483647"))))
        .containsExactly("amount -> {order.amount-too-large}");
    assertThat(violationsOf(limitBuyOf(10, new BigDecimal("100e2147483647"))))
        .containsExactly("price -> {order.price-too-large}");
  }

  @Test
  void buildsTheCommandWithTheUserOfTheAddress() {
    UUID clientOrderId = UUID.randomUUID();
    SubmitOrderRequest request =
        request(clientOrderId, TradeSide.SELL, OrderType.LIMIT, 5, null, new BigDecimal("900"));

    var command = request.toCommand(1);

    assertThat(command.userId()).isEqualTo(1);
    assertThat(command.clientOrderId()).isEqualTo(clientOrderId);
    assertThat(command.instrumentId()).isEqualTo(PAMP);
    assertThat(command.side()).isEqualTo(OrderSide.SELL);
    assertThat(command.type()).isEqualTo(OrderType.LIMIT);
    assertThat(command.quantity()).isEqualTo(5);
    assertThat(command.amount()).isNull();
    assertThat(command.limitPrice()).isEqualByComparingTo("900");
  }
}
