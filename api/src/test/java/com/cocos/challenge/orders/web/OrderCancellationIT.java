package com.cocos.challenge.orders.web;

import static com.cocos.challenge.support.Concurrency.WAIT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;

import com.cocos.challenge.orders.OrderRepository;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.Concurrency;
import com.cocos.challenge.support.Concurrency.PausePoint;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Cancelling a pending order (RFC 5.4). The simultaneous case holds the first cancellation open
 * right after its update, and lets it go once the second one waits for the lock of the order.
 */
@IntegrationTest
class OrderCancellationIT {

  private static final String CANCEL = "/users/{userId}/orders/{orderId}/cancel";
  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final long USER_ONE = 1;
  private static final long USER_TWO = 2;
  private static final long PAMP = 47;
  private static final long METR = 54;
  private static final long PENDING_PURCHASE = 7;
  private static final String ORDER_NOT_FOUND = "/problems/order-not-found";
  private static final String ORDER_NOT_CANCELLABLE = "/problems/order-not-cancellable";
  private static final String USER_NOT_FOUND = "/problems/user-not-found";
  private static final String VALIDATION_ERROR = "/problems/validation-error";
  private static final String CANCELLED = "CANCELLED";
  private static final String CLIENT_A = "0b8e2a1c-5d3f-4e6a-9c7b-1a2b3c4d5e6f";
  private static final String CLIENT_B = "7c1d9e3f-2a4b-4c5d-8e6f-9a0b1c2d3e4f";
  private static final String CLIENT_C = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d";
  private static final int SIMULTANEOUS_REQUESTS = 2;

  @LocalServerPort private int port;
  @Autowired private TestData data;
  @Autowired private Concurrency concurrency;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private OrderRepository orders;

  private ApiClient api;
  private ExecutorService requests;
  private @Nullable PausePoint pause;

  @BeforeEach
  void prepare() {
    api = new ApiClient(port);
    requests = Executors.newFixedThreadPool(SIMULTANEOUS_REQUESTS);
  }

  /** A failed test can leave its requests in the API: they must not cross the next test. */
  @AfterEach
  void finishTheRequests() throws InterruptedException {
    if (pause != null) {
      pause.release();
    }
    requests.shutdown();
    if (!requests.awaitTermination(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
      requests.shutdownNow();
      throw new AssertionError("The requests of the test still run after " + WAIT_LIMIT);
    }
  }

  private ApiResponse cancel(long userId, Object orderId) {
    return api.post(CANCEL.replace("{userId}", "" + userId).replace("{orderId}", "" + orderId), "");
  }

  private ApiResponse submit(long userId, String json) {
    return api.post("/users/" + userId + "/orders", json);
  }

  private static String order(
      String clientOrderId, long instrumentId, String sideAndType, String rest) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, %s, %s }\
    """
        .formatted(clientOrderId, instrumentId, sideAndType, rest);
  }

  private static String marketBuy(String clientOrderId, String sizing) {
    return order(clientOrderId, PAMP, "\"side\": \"BUY\", \"type\": \"MARKET\"", sizing);
  }

  private JsonNode portfolioOf(long userId) {
    return api.get(PORTFOLIO, userId).json();
  }

  private static JsonNode position(JsonNode portfolio, String ticker) {
    for (JsonNode position : portfolio.get("positions")) {
      if (position.get("ticker").asString().equals(ticker)) {
        return position;
      }
    }
    throw new AssertionError("No position for " + ticker);
  }

  private static String type(ApiResponse response) {
    return response.json().get("type").asString();
  }

  @Test
  void cancelsAPendingPurchaseAndReleasesItsCash() {
    ApiResponse response = cancel(USER_ONE, PENDING_PURCHASE);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("id").asLong()).isEqualTo(PENDING_PURCHASE);
    assertThat(response.json().get("ticker").asString()).isEqualTo("BMA");
    assertThat(response.json().get("status").asString()).isEqualTo(CANCELLED);
    JsonNode cash = portfolioOf(USER_ONE).get("cash");
    assertThat(cash.get("available").decimalValue()).isEqualByComparingTo("717500.00");
    assertThat(cash.get("balance").decimalValue()).isEqualByComparingTo("753000.00");
  }

  @Test
  void aRejectedPurchaseGetsItsCashBackOnceTheReservationIsCancelled() {
    String tooBig = "\"amount\": 700000";
    ApiResponse rejected = submit(USER_ONE, marketBuy(CLIENT_A, tooBig));
    assertThat(rejected.json().get("status").asString()).isEqualTo("REJECTED");

    cancel(USER_ONE, PENDING_PURCHASE);
    ApiResponse replay = submit(USER_ONE, marketBuy(CLIENT_A, tooBig));
    ApiResponse again = submit(USER_ONE, marketBuy(CLIENT_B, tooBig));

    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.json().get("id").asLong()).isEqualTo(rejected.json().get("id").asLong());
    assertThat(replay.json().get("status").asString()).isEqualTo("REJECTED");
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.json().get("status").asString()).isEqualTo("FILLED");
    assertThat(again.json().get("quantity").asInt()).isEqualTo(756);
    assertThat(again.json().get("amount").decimalValue()).isEqualByComparingTo("699942.60");
    assertThat(portfolioOf(USER_ONE).get("cash").get("available").decimalValue())
        .isEqualByComparingTo("17557.40");
  }

  @Test
  void cancellingAPendingSaleReleasesItsShares() {
    long user = data.createUser();
    data.deposit(user, 100_000);
    data.fill(user, PAMP, OrderSide.BUY, 100, new BigDecimal("900.00"));
    data.pending(user, PAMP, OrderSide.SELL, 30, new BigDecimal("1000.00"));
    long pendingSale = jdbc.sql("select max(id) from orders").query(Long.class).single();
    assertThat(position(portfolioOf(user), "PAMP").get("reservedQuantity").asLong()).isEqualTo(30);

    ApiResponse response = cancel(user, pendingSale);

    assertThat(response.status()).isEqualTo(200);
    assertThat(position(portfolioOf(user), "PAMP").get("reservedQuantity").asLong()).isZero();
  }

  @Test
  void aCancelledLimitOrderIsTheAnswerToItsRetry() {
    String limit =
        order(
            CLIENT_C,
            METR,
            "\"side\": \"BUY\", \"type\": \"LIMIT\"",
            "\"quantity\": 10, \"price\": 10000");
    ApiResponse pending = submit(USER_ONE, limit);
    assertThat(pending.status()).isEqualTo(201);
    assertThat(pending.json().get("status").asString()).isEqualTo("NEW");

    cancel(USER_ONE, pending.json().get("id").asLong());
    ApiResponse replay = submit(USER_ONE, limit);

    assertThat(replay.status()).isEqualTo(200);
    assertThat(replay.json().get("id").asLong()).isEqualTo(pending.json().get("id").asLong());
    assertThat(replay.json().get("status").asString()).isEqualTo(CANCELLED);
  }

  @ParameterizedTest
  @CsvSource({"3, CANCELLED", "2, FILLED", "6, REJECTED"})
  void refusesToCancelAnOrderThatIsNotPending(long orderId, String currentStatus) {
    JsonNode before = portfolioOf(USER_ONE);

    ApiResponse response = cancel(USER_ONE, orderId);

    assertThat(response.status()).isEqualTo(409);
    assertThat(type(response)).isEqualTo(ORDER_NOT_CANCELLABLE);
    assertThat(response.json().get("currentStatus").asString()).isEqualTo(currentStatus);
    assertThat(response.json().get("detail").asString())
        .isEqualTo(
            "La orden %d ya está %s: solo se cancela una orden NEW."
                .formatted(orderId, currentStatus));
    assertThat(portfolioOf(USER_ONE)).isEqualTo(before);
  }

  @Test
  void answers404ForAnOrderThatDoesNotExist() {
    ApiResponse response = cancel(USER_ONE, 999);

    assertThat(response.status()).isEqualTo(404);
    assertThat(type(response)).isEqualTo(ORDER_NOT_FOUND);
    assertThat(response.json().get("detail").asString()).isEqualTo("No existe la orden 999.");
  }

  @Test
  void answers404ForAnOrderOfAnotherUserAndLeavesItPending() {
    ApiResponse response = cancel(USER_TWO, PENDING_PURCHASE);

    assertThat(response.status()).isEqualTo(404);
    assertThat(type(response)).isEqualTo(ORDER_NOT_FOUND);
    assertThat(portfolioOf(USER_ONE).get("cash").get("available").decimalValue())
        .isEqualByComparingTo("627500.00");
  }

  @Test
  void answers404ForAUserThatDoesNotExist() {
    ApiResponse response = cancel(99, PENDING_PURCHASE);

    assertThat(response.status()).isEqualTo(404);
    assertThat(type(response)).isEqualTo(USER_NOT_FOUND);
  }

  @Test
  void answers400ForAnOrderIdThatIsNotANumber() {
    ApiResponse response = cancel(USER_ONE, "x");

    assertThat(response.status()).isEqualTo(400);
    assertThat(type(response)).isEqualTo(VALIDATION_ERROR);
  }

  @Test
  void twoSimultaneousCancellationsOfTheSameOrderCancelItOnce() throws Exception {
    PausePoint afterTheUpdate =
        PausePoint.after(orders, repository -> repository.cancelIfNew(anyLong(), anyLong()));
    pause = afterTheUpdate;
    Future<ApiResponse> first = requests.submit(() -> cancel(USER_ONE, PENDING_PURCHASE));
    afterTheUpdate.awaitReached(WAIT_LIMIT);
    Future<ApiResponse> second = requests.submit(() -> cancel(USER_ONE, PENDING_PURCHASE));
    concurrency.awaitBlockedOnLock(WAIT_LIMIT);
    afterTheUpdate.release();

    List<ApiResponse> responses =
        List.of(
            first.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS),
            second.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS));

    assertThat(responses).extracting(ApiResponse::status).containsExactly(200, 409);
    assertThat(type(responses.getLast())).isEqualTo(ORDER_NOT_CANCELLABLE);
    assertThat(responses.getLast().json().get("currentStatus").asString()).isEqualTo(CANCELLED);
  }
}
