package com.cocos.challenge.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import com.cocos.challenge.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class AccountRepositoryIT {

  private static final long USER_ONE = 1;
  private static final long UNKNOWN_USER = 99;
  private static final Duration TIMEOUT = Duration.ofMillis(200);

  @Autowired private AccountRepository repository;
  @Autowired private TransactionTemplate transaction;

  @Test
  void findsTheFilledAndPendingEntriesOfAUserInChronologicalOrder() {
    List<AccountEntry> entries = repository.findEntries(USER_ONE);

    assertThat(entries).hasSize(9);
    assertThat(entries.get(0).side()).isEqualTo(OrderSide.CASH_IN);
    assertThat(entries.get(0).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(entries.get(0).size()).isEqualTo(1_000_000);
    assertThat(entries.get(0).price()).isEqualByComparingTo("1");
    assertThat(entries.get(3).side()).isEqualTo(OrderSide.BUY);
    assertThat(entries.get(3).status()).isEqualTo(OrderStatus.NEW);
    assertThat(entries.get(3).instrumentId()).isEqualTo(45);
    assertThat(entries.get(8).side()).isEqualTo(OrderSide.SELL);
    assertThat(entries.get(8).instrumentId()).isEqualTo(31);
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
