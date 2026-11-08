package com.cocos.challenge.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cocos.challenge.support.Concurrency;
import com.cocos.challenge.support.Concurrency.HeldTurn;
import com.cocos.challenge.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@TestPropertySource(properties = "cocos.account.turn-timeout=200ms")
class AccountServiceIT {

  private static final long USER_ONE = 1;
  private static final long UNKNOWN_USER = 99;
  private static final Duration MAX_FAILURE_TIME = Duration.ofMillis(1500);

  @Autowired private AccountService service;
  @Autowired private TransactionTemplate transaction;
  @Autowired private Concurrency concurrency;

  @Test
  void loadsTheAccountOfUserOne() {
    Account account = service.load(USER_ONE);

    assertThat(account.cashBalance()).isEqualByComparingTo("753000.00");
    assertThat(account.availableCash()).isEqualByComparingTo("627500.00");
  }

  @Test
  void loadFailsForAnUnknownUser() {
    assertThatThrownBy(() -> service.load(UNKNOWN_USER)).isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void ensureExistsFailsForAnUnknownUser() {
    assertThatThrownBy(() -> service.ensureExists(UNKNOWN_USER))
        .isInstanceOf(UserNotFoundException.class);
    assertThatCode(() -> service.ensureExists(USER_ONE)).doesNotThrowAnyException();
  }

  @Test
  void takeTurnFailsForAnUnknownUser() {
    assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.takeTurn(UNKNOWN_USER)))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void takeTurnFailsFastWhenAnotherTransactionHoldsIt() throws InterruptedException {
    try (HeldTurn held = concurrency.holdTurn(USER_ONE)) {
      long start = System.nanoTime();
      assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.takeTurn(USER_ONE)))
          .isInstanceOf(UserBusyException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(MAX_FAILURE_TIME);
    }
  }

  @Test
  void takeTurnRequiresATransaction() {
    assertThatThrownBy(() -> service.takeTurn(USER_ONE))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void userBusyAsksToRetryInOneSecond() {
    assertThat(new UserBusyException().retryAfter()).contains(Duration.ofSeconds(1));
  }
}
