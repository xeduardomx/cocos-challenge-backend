package com.cocos.challenge.orders.web;

import static com.cocos.challenge.support.Concurrency.WAIT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A submission that cannot get the turn of its user in time (RFC 5.2). A class of its own: the
 * short wait is for the whole Spring context, and the tests with a pause could get a 503 with it on
 * a slow machine.
 */
@IntegrationTest
@TestPropertySource(properties = "cocos.account.turn-timeout=200ms")
class OrderTurnTimeoutIT {

  private static final long USER_ONE = 1;
  private static final long PAMP = 47;
  private static final int ORIGINAL_ORDERS = 11;
  private static final String LOCK_USER = "select id from users where id = :id for update";

  @LocalServerPort private int port;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcClient jdbc;

  private int orderCount() {
    return jdbc.sql("select count(*) from orders").query(Integer.class).single();
  }

  @Test
  void answersBusyWhenTheTurnIsNotFree() throws Exception {
    String request =
        """
        { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "MARKET", \
        "quantity": 10 }\
        """
            .formatted(UUID.randomUUID(), PAMP);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      Future<?> holder =
          other.submit(() ->
              transaction.executeWithoutResult(status -> {
                jdbc.sql(LOCK_USER).param("id", USER_ONE).query().singleRow();
                held.countDown();
                awaitRelease(release);
              }));
      assertThat(held.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

      ApiResponse response = new ApiClient(port).post("/users/" + USER_ONE + "/orders", request);

      release.countDown();
      holder.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
      assertThat(response.status()).isEqualTo(503);
      assertThat(response.json().get("type").asString()).isEqualTo("/problems/user-busy");
      assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
      assertThat(response.json().get("detail").asString())
          .isEqualTo("Tu cuenta está procesando otra orden. Intentá de nuevo.");
      assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS);
    } finally {
      release.countDown();
      other.shutdownNow();
    }
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      release.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
