package com.cocos.challenge.account;

import static com.cocos.challenge.shared.domain.OrderSide.BUY;
import static com.cocos.challenge.shared.domain.OrderSide.CASH_IN;
import static com.cocos.challenge.shared.domain.OrderSide.CASH_OUT;
import static com.cocos.challenge.shared.domain.OrderSide.SELL;
import static com.cocos.challenge.shared.domain.OrderStatus.FILLED;
import static com.cocos.challenge.shared.domain.OrderStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AccountCalculatorTest {

  private static final long PAMP = 47;
  private static final long LOMA = 45;
  private static final long BMA = 31;
  private static final long METR = 54;
  private static final long ARS = 66;

  private final AccountCalculator calculator = new AccountCalculator();

  /** The FILLED and NEW orders of user 1 in database.sql, in chronological order. */
  private static List<AccountEntry> userOne() {
    return List.of(
        entry(ARS, CASH_IN, FILLED, 1_000_000, "1"),
        entry(PAMP, BUY, FILLED, 50, "930"),
        entry(PAMP, SELL, FILLED, 10, "940"),
        entry(LOMA, BUY, NEW, 50, "710"),
        entry(BMA, BUY, NEW, 60, "1500"),
        entry(ARS, CASH_OUT, FILLED, 100_000, "1"),
        entry(BMA, BUY, FILLED, 20, "1540"),
        entry(METR, BUY, FILLED, 500, "250"),
        entry(BMA, SELL, FILLED, 30, "1530"));
  }

  private static AccountEntry entry(
      long instrumentId, OrderSide side, OrderStatus status, int size, String price) {
    return new AccountEntry(instrumentId, side, status, size, new BigDecimal(price));
  }

  private static List<AccountEntry> userOneWith(AccountEntry... more) {
    List<AccountEntry> entries = new ArrayList<>(userOne());
    entries.addAll(List.of(more));
    return entries;
  }

  private static Position positionOf(Account account, long instrumentId) {
    return Objects.requireNonNull(account.positions().get(instrumentId));
  }

  @Test
  void calculatesTheCashOfUserOne() {
    Account account = calculator.calculate(userOne());

    assertThat(account.cashBalance()).isEqualByComparingTo("753000.00");
    assertThat(account.reservedCash()).isEqualByComparingTo("125500.00");
    assertThat(account.availableCash()).isEqualByComparingTo("627500.00");
  }

  @Test
  void calculatesThePositionsOfUserOne() {
    Account account = calculator.calculate(userOne());

    assertThat(account.heldPositions())
        .extracting(Position::instrumentId)
        .containsExactlyInAnyOrder(METR, PAMP, BMA);
    assertThat(positionOf(account, METR).quantity()).isEqualTo(500);
    assertThat(positionOf(account, METR).averagePrice()).isEqualByComparingTo("250");
    assertThat(positionOf(account, PAMP).quantity()).isEqualTo(40);
    assertThat(positionOf(account, PAMP).averagePrice()).isEqualByComparingTo("930");
    assertThat(positionOf(account, BMA).quantity()).isEqualTo(-10);
    assertThat(positionOf(account, BMA).averagePrice()).isNull();
    assertThat(account.positions()).doesNotContainKey(ARS);
  }

  @Test
  void reservesTheSharesOfAPendingSale() {
    Account account = calculator.calculate(userOneWith(entry(PAMP, SELL, NEW, 30, "950")));

    Position pamp = positionOf(account, PAMP);
    assertThat(pamp.reservedQuantity()).isEqualTo(30);
    assertThat(pamp.availableQuantity()).isEqualTo(10);
    assertThat(account.availableShares(PAMP)).isEqualTo(10);
    assertThat(account.reservedCash()).isEqualByComparingTo("125500.00");
  }

  @Test
  void ignoresSalesInTheAveragePrice() {
    Account account =
        calculator.calculate(
            List.of(
                entry(PAMP, BUY, FILLED, 10, "100"),
                entry(PAMP, BUY, FILLED, 10, "200"),
                entry(PAMP, SELL, FILLED, 5, "300")));

    Position pamp = positionOf(account, PAMP);
    assertThat(pamp.quantity()).isEqualTo(15);
    assertThat(pamp.averagePrice()).isEqualByComparingTo("150");
  }

  @Test
  void restartsTheAveragePriceWhenThePositionComesBackFromNegative() {
    Account account = calculator.calculate(userOneWith(entry(BMA, BUY, FILLED, 15, "1502.80")));

    Position bma = positionOf(account, BMA);
    assertThat(bma.quantity()).isEqualTo(5);
    assertThat(bma.averagePrice()).isEqualByComparingTo("1502.80");
  }

  @Test
  void restartsTheAveragePriceOnEveryPurchaseMadeAtOrBelowZero() {
    Account account =
        calculator.calculate(
            userOneWith(entry(BMA, BUY, FILLED, 5, "1500"), entry(BMA, BUY, FILLED, 15, "1600")));

    Position bma = positionOf(account, BMA);
    assertThat(bma.quantity()).isEqualTo(10);
    assertThat(bma.averagePrice()).isEqualByComparingTo("1600");
  }

  @Test
  void returnsAnEmptyAccountWithoutEntries() {
    Account account = calculator.calculate(List.of());

    assertThat(account.cashBalance()).isEqualTo(new BigDecimal("0.00"));
    assertThat(account.reservedCash()).isEqualTo(new BigDecimal("0.00"));
    assertThat(account.availableCash()).isEqualTo(new BigDecimal("0.00"));
    assertThat(account.positions()).isEmpty();
    assertThat(account.heldPositions()).isEmpty();
    assertThat(account.availableShares(PAMP)).isZero();
  }
}
