package com.cocos.challenge.account;

import static com.cocos.challenge.shared.domain.OrderSide.BUY;
import static com.cocos.challenge.shared.domain.OrderSide.CASH_IN;
import static com.cocos.challenge.shared.domain.OrderSide.CASH_OUT;
import static com.cocos.challenge.shared.domain.OrderSide.SELL;
import static com.cocos.challenge.shared.domain.OrderStatus.FILLED;
import static com.cocos.challenge.shared.domain.OrderStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.cocos.challenge.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class AccountRepositoryIT {

  private static final long USER_ONE = 1;
  private static final long UNKNOWN_USER = 99;
  private static final long PAMP = 47;
  private static final long LOMA = 45;
  private static final long BMA = 31;
  private static final long METR = 54;
  private static final long ARS = 66;
  private static final Duration TIMEOUT = Duration.ofMillis(200);

  @Autowired private AccountRepository repository;
  @Autowired private TransactionTemplate transaction;

  @Test
  void findsTheFilledAndPendingEntriesOfAUserInChronologicalOrder() {
    List<AccountEntry> entries = repository.findEntries(USER_ONE);

    assertThat(entries)
        .extracting(
            AccountEntry::instrumentId,
            AccountEntry::side,
            AccountEntry::status,
            AccountEntry::size)
        .containsExactly(
            tuple(ARS, CASH_IN, FILLED, 1_000_000),
            tuple(PAMP, BUY, FILLED, 50),
            tuple(PAMP, SELL, FILLED, 10),
            tuple(LOMA, BUY, NEW, 50),
            tuple(BMA, BUY, NEW, 60),
            tuple(ARS, CASH_OUT, FILLED, 100_000),
            tuple(BMA, BUY, FILLED, 20),
            tuple(METR, BUY, FILLED, 500),
            tuple(BMA, SELL, FILLED, 30));
    assertThat(entries)
        .extracting(AccountEntry::price)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(
            decimal("1"),
            decimal("930"),
            decimal("940"),
            decimal("710"),
            decimal("1500"),
            decimal("1"),
            decimal("1540"),
            decimal("250"),
            decimal("1530"));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  @Test
  void findsNothingForAnUnknownUser() {
    assertThat(repository.findEntries(UNKNOWN_USER)).isEmpty();
  }

  @Test
  void tellsWhetherAUserExists() {
    assertThat(repository.exists(USER_ONE)).isTrue();
    assertThat(repository.exists(UNKNOWN_USER)).isFalse();
  }

  @Test
  void takesTheTurnOfAnExistingUser() {
    Boolean locked = transaction.execute(status -> repository.lockForTurn(USER_ONE, TIMEOUT));

    assertThat(locked).isTrue();
  }

  @Test
  void doesNotLockAnUnknownUser() {
    Boolean locked = transaction.execute(status -> repository.lockForTurn(UNKNOWN_USER, TIMEOUT));

    assertThat(locked).isFalse();
  }
}
