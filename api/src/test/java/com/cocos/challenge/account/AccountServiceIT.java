package com.cocos.challenge.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cocos.challenge.support.IntegrationTest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@TestPropertySource(properties = "cocos.account.turn-timeout=200ms")
class AccountServiceIT {

  private static final long USER_ONE = 1;
  private static final long UNKNOWN_USER = 99;
  private static final Duration MAX_FAILURE_TIME = Duration.ofSeconds(1);
  private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

  @Autowired private AccountService service;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcClient jdbc;

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
  void takeTurnFailsFastWhenAnotherTransactionHoldsIt() throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      Future<?> holder =
          other.submit(() ->
              transaction.executeWithoutResult(status -> {
                jdbc.sql("select id from users where id = :id for update")
                    .param("id", USER_ONE)
                    .query()
                    .singleRow();
                held.countDown();
                awaitRelease(release);
              }));
      assertThat(held.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

      long start = System.nanoTime();
      assertThatThrownBy(() -> transaction.executeWithoutResult(s -> service.takeTurn(USER_ONE)))
          .isInstanceOf(UserBusyException.class);
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

      assertThat(elapsed).isLessThan(MAX_FAILURE_TIME);
      release.countDown();
      holder.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    } finally {
      release.countDown();
      other.shutdownNow();
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

  private static void awaitRelease(CountDownLatch release) {
    try {
      release.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
