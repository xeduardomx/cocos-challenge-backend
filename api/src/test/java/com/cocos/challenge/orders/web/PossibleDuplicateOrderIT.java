package com.cocos.challenge.orders.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The same order sent again with another client order id (RFC 5.5, cases 21 to 26 of the
 * submission). The client order id cannot tell it is a repetition, so an order that would be
 * accepted is refused while the user has an equal one accepted moments ago.
 */
@IntegrationTest
class PossibleDuplicateOrderIT {

  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final long USER_ONE = 1;
  private static final long USER_TWO = 2;
  private static final long PAMP = 47;
  private static final String PAMP_TICKER = "PAMP";
  private static final int ORIGINAL_ORDERS = 11;
  private static final int PAMP_HELD = 40;
  private static final String POSSIBLE_DUPLICATE = "/problems/possible-duplicate-order";
  private static final String ONE = "\"quantity\": 1";
  private static final String TEN = "\"quantity\": 10";
  private static final String ELEVEN = "\"quantity\": 11";
  private static final String LIMIT_PRICE = "900";
  private static final int DEPOSIT = 1_000_000;
  private static final int CASH_FOR_ONE_PURCHASE = 10_000;
  private static final Duration WINDOW = Duration.ofSeconds(5);
  private static final Duration ONE_SECOND = Duration.ofSeconds(1);

  @LocalServerPort private int port;
  @Autowired private TestData data;
  @Autowired private JdbcClient jdbc;

  private ApiClient api;

  @BeforeEach
  void createClient() {
    api = new ApiClient(port);
  }

  /** A MARKET purchase with a client order id of its own. */
  private static String buy(long instrumentId, String sizing) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "MARKET", %s }\
    """
        .formatted(UUID.randomUUID(), instrumentId, sizing);
  }

  /** A LIMIT purchase with a client order id of its own. */
  private static String buyLimit(long instrumentId, String sizing, String price) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "LIMIT", %s, "price": %s }\
    """
        .formatted(UUID.randomUUID(), instrumentId, sizing, price);
  }

  private ApiResponse submit(long userId, String json) {
    return api.post("/users/" + userId + "/orders", json);
  }

  private ApiResponse cancel(long userId, long orderId) {
    return api.post("/users/" + userId + "/orders/" + orderId + "/cancel", "");
  }

  private static String statusOf(ApiResponse response) {
    return response.json().get("status").asString();
  }

  private int orderCount() {
    return jdbc.sql("select count(*) from orders").query(Integer.class).single();
  }

  private int positionOf(long userId, String ticker) {
    for (JsonNode position : api.get(PORTFOLIO, userId).json().get("positions")) {
      if (position.get("ticker").asString().equals(ticker)) {
        return position.get("quantity").asInt();
      }
    }
    throw new AssertionError("No position for " + ticker);
  }

  @Test
  void refusesTheSameOrderSentRightAwayWithAnotherIdentifier() {
    long first = submit(USER_ONE, buy(PAMP, TEN)).json().get("id").asLong();

    ApiResponse second = submit(USER_ONE, buy(PAMP, TEN));

    assertThat(second.status()).isEqualTo(409);
    JsonNode problem = second.json();
    assertThat(problem.get("type").asString()).isEqualTo(POSSIBLE_DUPLICATE);
    assertThat(problem.get("title").asString()).isEqualTo("Posible orden duplicada");
    assertThat(problem.get("existingOrderId").asLong()).isEqualTo(first);
    assertThat(problem.get("detail").asString())
        .isEqualTo(
            "Acaba de entrar una orden igual, la 12. Si querés otra igual, esperá unos segundos y"
                + " volvé a enviarla.");
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
    assertThat(positionOf(USER_ONE, PAMP_TICKER)).isEqualTo(PAMP_HELD + 10);
  }

  @Test
  void acceptsTheSameOrderOnceTheFirstOneIsOlderThanTheWindow() {
    long first = submit(USER_ONE, buy(PAMP, TEN)).json().get("id").asLong();
    data.makeOlder(first, WINDOW.plus(ONE_SECOND));

    ApiResponse second = submit(USER_ONE, buy(PAMP, TEN));

    assertThat(second.status()).isEqualTo(201);
    assertThat(second.json().get("status").asString()).isEqualTo("FILLED");
    assertThat(positionOf(USER_ONE, PAMP_TICKER)).isEqualTo(PAMP_HELD + 20);
  }

  @Test
  void stillRefusesTheSameOrderJustBeforeTheWindowEnds() {
    long first = submit(USER_ONE, buy(PAMP, TEN)).json().get("id").asLong();
    data.makeOlder(first, WINDOW.minus(ONE_SECOND));

    ApiResponse second = submit(USER_ONE, buy(PAMP, TEN));

    assertThat(second.status()).isEqualTo(409);
    assertThat(second.json().get("existingOrderId").asLong()).isEqualTo(first);
  }

  @Test
  void aPendingLimitOrderCountsAsTheFirstOne() {
    long first = submit(USER_ONE, buyLimit(PAMP, TEN, LIMIT_PRICE)).json().get("id").asLong();

    ApiResponse second = submit(USER_ONE, buyLimit(PAMP, TEN, LIMIT_PRICE));

    assertThat(second.status()).isEqualTo(409);
    assertThat(second.json().get("existingOrderId").asLong()).isEqualTo(first);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  @Test
  void anotherUserCanSendTheSameOrder() {
    long other = data.createUser();
    data.deposit(other, DEPOSIT);
    assertThat(statusOf(submit(USER_ONE, buy(PAMP, TEN)))).isEqualTo("FILLED");

    ApiResponse second = submit(other, buy(PAMP, TEN));

    assertThat(second.status()).isEqualTo(201);
    assertThat(statusOf(second)).isEqualTo("FILLED");
  }

  @Test
  void anOrderWithAnotherQuantityIsNotADuplicate() {
    assertThat(statusOf(submit(USER_ONE, buy(PAMP, TEN)))).isEqualTo("FILLED");

    ApiResponse second = submit(USER_ONE, buy(PAMP, ELEVEN));

    assertThat(second.status()).isEqualTo(201);
    assertThat(statusOf(second)).isEqualTo("FILLED");
    assertThat(positionOf(USER_ONE, PAMP_TICKER)).isEqualTo(PAMP_HELD + 21);
  }

  @Test
  void anOrderThatWasRejectedDoesNotCountAsTheFirstOne() {
    assertThat(statusOf(submit(USER_TWO, buy(PAMP, ONE)))).isEqualTo("REJECTED");
    data.deposit(USER_TWO, DEPOSIT);

    ApiResponse second = submit(USER_TWO, buy(PAMP, ONE));

    assertThat(second.status()).isEqualTo(201);
    assertThat(statusOf(second)).isEqualTo("FILLED");
  }

  @Test
  void anOrderThatWasCancelledDoesNotCountAsTheFirstOne() {
    long first = submit(USER_ONE, buyLimit(PAMP, TEN, LIMIT_PRICE)).json().get("id").asLong();
    assertThat(statusOf(cancel(USER_ONE, first))).isEqualTo("CANCELLED");

    ApiResponse second = submit(USER_ONE, buyLimit(PAMP, TEN, LIMIT_PRICE));

    assertThat(second.status()).isEqualTo(201);
    assertThat(statusOf(second)).isEqualTo("NEW");
  }

  @Test
  void recordsAsRejectedAnEqualOrderThatTheAccountCannotAfford() {
    long user = data.createUser();
    data.deposit(user, CASH_FOR_ONE_PURCHASE);
    assertThat(statusOf(submit(user, buy(PAMP, TEN)))).isEqualTo("FILLED");

    ApiResponse second = submit(user, buy(PAMP, TEN));

    assertThat(second.status()).isEqualTo(201);
    assertThat(statusOf(second)).isEqualTo("REJECTED");
    assertThat(second.json().get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_CASH");
  }
}
