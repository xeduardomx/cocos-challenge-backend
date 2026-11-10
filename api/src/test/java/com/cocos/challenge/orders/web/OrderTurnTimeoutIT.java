package com.cocos.challenge.orders.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.Concurrency;
import com.cocos.challenge.support.Concurrency.HeldTurn;
import com.cocos.challenge.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

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

  @LocalServerPort private int port;
  @Autowired private Concurrency concurrency;
  @Autowired private JdbcClient jdbc;

  private int orderCount() {
    return jdbc.sql("select count(*) from orders").query(Integer.class).single();
  }

  @Test
  void answersBusyWhenTheTurnIsNotFree() throws InterruptedException {
    String request =
        """
        { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "MARKET", \
        "quantity": 10 }\
        """
            .formatted(UUID.randomUUID(), PAMP);

    ApiResponse response;
    try (HeldTurn held = concurrency.holdTurn(USER_ONE)) {
      response = new ApiClient(port).post("/users/" + USER_ONE + "/orders", request);
    }

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.json().get("type").asString()).isEqualTo("/problems/user-busy");
    assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(response.json().get("detail").asString())
        .isEqualTo("Tu cuenta está procesando otra orden. Intentá de nuevo.");
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS);
  }
}
