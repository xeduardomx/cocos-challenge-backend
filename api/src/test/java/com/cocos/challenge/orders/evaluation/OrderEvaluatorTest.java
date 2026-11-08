package com.cocos.challenge.orders.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.account.Position;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.orders.RejectionReason;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class OrderEvaluatorTest {

  private static final long BMA = 31;
  private static final long METR = 54;
  private static final long PAMP = 47;
  private static final long PGR = 3;
  private static final BigDecimal PAMP_PRICE = new BigDecimal("925.85");

  private final OrderEvaluator evaluator =
      new OrderEvaluator(
          List.of(new QuotedInstrumentRule(), new EnoughCashRule(), new EnoughSharesRule()));

  /** Available cash 627,500.00 and 40 PAMP, as in the PRD. */
  private static Account user1() {
    return new Account(
        new BigDecimal("753000.00"),
        new BigDecimal("125500.00"),
        Map.of(
            PAMP, new Position(PAMP, 40, 0, new BigDecimal("930.00")),
            METR, new Position(METR, 500, 0, new BigDecimal("250.00")),
            BMA, new Position(BMA, -10, 0, null)));
  }

  private static Account emptyAccount() {
    return new Account(new BigDecimal("0.00"), new BigDecimal("0.00"), Map.of());
  }

  private static OrderCandidate marketBuy(
      long instrumentId, int quantity, @Nullable BigDecimal price) {
    return new OrderCandidate(
        instrumentId, OrderSide.BUY, OrderType.MARKET, quantity, price, price != null);
  }

  private static OrderCandidate limitBuy(
      long instrumentId, int quantity, BigDecimal limit, boolean quoted) {
    return new OrderCandidate(
        instrumentId, OrderSide.BUY, OrderType.LIMIT, quantity, limit, quoted);
  }

  private static OrderCandidate marketSell(long instrumentId, int quantity, BigDecimal price) {
    return new OrderCandidate(
        instrumentId, OrderSide.SELL, OrderType.MARKET, quantity, price, true);
  }

  private static void assertRejectedFor(OrderDecision decision, RejectionReason reason) {
    assertThat(decision.status()).isEqualTo(OrderStatus.REJECTED);
    assertThat(decision.rejectionReason()).isEqualTo(reason);
  }

  @Test
  void fillsAMarketPurchaseTheCashCovers() {
    OrderDecision decision = evaluator.evaluate(marketBuy(PAMP, 10, PAMP_PRICE), user1());

    assertThat(decision).isEqualTo(new OrderDecision(OrderStatus.FILLED, null));
  }

  @Test
  void rejectsAPurchaseThatNeedsMoreThanTheAvailableCash() {
    assertRejectedFor(
        evaluator.evaluate(marketBuy(PAMP, 756, PAMP_PRICE), user1()),
        RejectionReason.INSUFFICIENT_CASH);
    assertRejectedFor(
        evaluator.evaluate(marketBuy(PAMP, 680, PAMP_PRICE), user1()),
        RejectionReason.INSUFFICIENT_CASH);
  }

  @Test
  void fillsAPurchaseThatCostsExactlyTheAvailableCash() {
    Account exact = new Account(new BigDecimal("9258.50"), new BigDecimal("0.00"), Map.of());

    OrderDecision decision = evaluator.evaluate(marketBuy(PAMP, 10, PAMP_PRICE), exact);

    assertThat(decision.status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void rejectsALimitPurchaseThatReservesMoreThanTheAvailableCash() {
    OrderDecision decision =
        evaluator.evaluate(limitBuy(METR, 100, new BigDecimal("10000"), true), user1());

    assertRejectedFor(decision, RejectionReason.INSUFFICIENT_CASH);
  }

  @Test
  void leavesALimitPurchaseTheCashCoversAsNew() {
    OrderDecision decision =
        evaluator.evaluate(limitBuy(METR, 10, new BigDecimal("10000"), true), user1());

    assertThat(decision).isEqualTo(new OrderDecision(OrderStatus.NEW, null));
  }

  @Test
  void rejectsASaleOfMoreSharesThanTheUserHolds() {
    assertRejectedFor(
        evaluator.evaluate(marketSell(PAMP, 50, PAMP_PRICE), user1()),
        RejectionReason.INSUFFICIENT_SHARES);
  }

  @Test
  void fillsASaleOfTheSharesTheUserHolds() {
    OrderDecision decision = evaluator.evaluate(marketSell(PAMP, 40, PAMP_PRICE), user1());

    assertThat(decision.status()).isEqualTo(OrderStatus.FILLED);
  }

  @Test
  void rejectsASaleOfSharesAlreadyReservedByAPendingSale() {
    Account withPendingSale =
        new Account(
            new BigDecimal("753000.00"),
            new BigDecimal("125500.00"),
            Map.of(PAMP, new Position(PAMP, 40, 30, new BigDecimal("930.00"))));

    OrderDecision decision = evaluator.evaluate(marketSell(PAMP, 20, PAMP_PRICE), withPendingSale);

    assertRejectedFor(decision, RejectionReason.INSUFFICIENT_SHARES);
  }

  @Test
  void rejectsAMarketOrderOnAnInstrumentWithoutQuote() {
    assertRejectedFor(
        evaluator.evaluate(marketBuy(PGR, 10, null), user1()), RejectionReason.NO_MARKET_PRICE);
  }

  @Test
  void rejectsALimitOrderOnAnInstrumentWithoutQuote() {
    assertRejectedFor(
        evaluator.evaluate(limitBuy(PGR, 10, new BigDecimal("50.00"), false), user1()),
        RejectionReason.NO_MARKET_PRICE);
  }

  @Test
  void reportsTheMissingQuoteBeforeTheMissingCash() {
    assertRejectedFor(
        evaluator.evaluate(limitBuy(PGR, 10, new BigDecimal("50.00"), false), emptyAccount()),
        RejectionReason.NO_MARKET_PRICE);
  }

  @Test
  void rejectsAPurchaseOfAUserWithAnEmptyAccount() {
    assertRejectedFor(
        evaluator.evaluate(marketBuy(PAMP, 1, PAMP_PRICE), emptyAccount()),
        RejectionReason.INSUFFICIENT_CASH);
  }

  @Test
  void rejectsASaleOfAUserWithAnEmptyAccount() {
    assertRejectedFor(
        evaluator.evaluate(marketSell(PAMP, 1, PAMP_PRICE), emptyAccount()),
        RejectionReason.INSUFFICIENT_SHARES);
  }

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
