package com.cocos.challenge.orders.web;

import static com.cocos.challenge.support.OrderRequests.marketBuy;
import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.account.UserBusyException;
import com.cocos.challenge.shared.web.RequestIds;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.Concurrency;
import com.cocos.challenge.support.Concurrency.HeldTurn;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.LogLines;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "cocos.account.turn-timeout=200ms")
class OrderTurnTimeoutIT {

  private static final long USER_ONE = 1;
  private static final String QUANTITY_TEN = "\"quantity\": 10";
  private static final int ORIGINAL_ORDERS = 11;

  @LocalServerPort private int port;
  @Autowired private Concurrency concurrency;
  @Autowired private JdbcClient jdbc;

  private int orderCount() {
    return jdbc.sql("select count(*) from orders").query(Integer.class).single();
  }

  @Test
  void answersBusyWhenTheTurnIsNotFree() throws InterruptedException {
    ApiResponse response;
    try (HeldTurn held = concurrency.holdTurn(USER_ONE)) {
      response =
          new ApiClient(port)
              .post("/users/" + USER_ONE + "/orders", marketBuy(UUID.randomUUID(), QUANTITY_TEN));
    }

    assertThat(response.status()).isEqualTo(503);
    assertThat(response.json().get("type").asString()).isEqualTo("/problems/user-busy");
    assertThat(response.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(response.json().get("detail").asString())
        .isEqualTo("Tu cuenta está procesando otra orden. Intentá de nuevo.");
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS);
  }

  @Test
  void writesTheBusyTurnAsOneErrorWithItsStackTrace(CapturedOutput output)
      throws InterruptedException {
    String requestId = UUID.randomUUID().toString();
    HttpHeaders headers = new HttpHeaders();
    headers.set(RequestIds.HEADER, requestId);

    ApiResponse response;
    try (HeldTurn held = concurrency.holdTurn(USER_ONE)) {
      response =
          new ApiClient(port, headers)
              .post("/users/" + USER_ONE + "/orders", marketBuy(UUID.randomUUID(), QUANTITY_TEN));
    }

    assertThat(response.status()).isEqualTo(503);
    assertThat(LogLines.errorsOfRequest(output, requestId))
        .singleElement()
        .satisfies(line -> {
          assertThat(LogLines.level(line)).isEqualTo("ERROR");
          assertThat(LogLines.message(line)).isEqualTo("Request failed");
          assertThat(LogLines.httpStatus(line)).isEqualTo(503);
          assertThat(LogLines.problemType(line)).isEqualTo("/problems/user-busy");
          assertThat(LogLines.stackTrace(line)).contains(UserBusyException.class.getName());
        });
  }
}
