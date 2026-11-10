package com.cocos.challenge.orders.web;

import static com.cocos.challenge.support.Concurrency.WAIT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cocos.challenge.account.repository.AccountRepository;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.Concurrency;
import com.cocos.challenge.support.Concurrency.PausePoint;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Two orders of the same user at the same time (RFC 5.2, 5.3 and 10.3). The first one waits right
 * after reading its account, and the test lets it go once the second one waits for the turn of the
 * user. In the sales and the purchases the test also lets it go if the second one reads its account
 * instead: without the turn, both always read the same account, and the outcome shows it.
 */
@IntegrationTest
class OrderConcurrencyIT {

  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final long USER_ONE = 1;
  private static final long BMA = 31;
  private static final long PAMP = 47;
  private static final String BMA_TICKER = "BMA";
  private static final BigDecimal BMA_PRICE = new BigDecimal("1502.80");
  private static final int DEPOSIT = 1_000_000;
  private static final int BMA_HELD = 20;
  private static final int ORIGINAL_ORDERS = 11;
  private static final int SIMULTANEOUS_REQUESTS = 2;

  @LocalServerPort private int port;
  @Autowired private TestData data;
  @Autowired private Concurrency concurrency;
  @Autowired private JdbcClient jdbc;
  @MockitoSpyBean private AccountRepository accounts;

  private ApiClient api;
  private ExecutorService requests;
  private PausePoint pause;

  @BeforeEach
  void prepare() {
    api = new ApiClient(port);
    requests = Executors.newFixedThreadPool(SIMULTANEOUS_REQUESTS);
    pause = PausePoint.after(accounts, repository -> repository.findEntries(anyLong()));
  }

  /** A failed test can leave its requests in the API: they must not cross the next test. */
  @AfterEach
  void finishTheRequests() throws InterruptedException {
    pause.release();
    requests.shutdown();
    if (!requests.awaitTermination(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
      requests.shutdownNow();
      throw new AssertionError("The requests of the test still run after " + WAIT_LIMIT);
    }
  }

  private static String marketOrder(OrderSide side, long instrumentId, String sizing) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "%s", "type": "MARKET", %s }\
    """
        .formatted(UUID.randomUUID(), instrumentId, side.name(), sizing);
  }

  private ApiResponse submit(long userId, String json) {
    return api.post("/users/" + userId + "/orders", json);
  }

  /**
   * Sends both requests so they cross: the first one waits after reading its account until {@code
   * awaitSecond} sees the second one arrive.
   */
  private List<ApiResponse> submitTogether(
      long userId, String first, String second, Runnable awaitSecond) throws Exception {
    Future<ApiResponse> firstResponse = requests.submit(() -> submit(userId, first));
    pause.awaitReached(WAIT_LIMIT);
    Future<ApiResponse> secondResponse = requests.submit(() -> submit(userId, second));
    awaitSecond.run();
    pause.release();
    return List.of(responseOf(firstResponse), responseOf(secondResponse));
  }

  private void awaitTheTurnOrTheSecondRead() {
    concurrency.awaitBlockedOnLockOrSecondCall(pause, WAIT_LIMIT);
  }

  private void awaitTheTurn() {
    concurrency.awaitBlockedOnLock(WAIT_LIMIT);
  }

  private static ApiResponse responseOf(Future<ApiResponse> response) throws Exception {
    return response.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private static List<String> statuses(List<ApiResponse> responses) {
    return responses.stream().map(response -> response.json().get("status").asString()).toList();
  }

  private static JsonNode withStatus(List<ApiResponse> responses, String status) {
    return responses.stream()
        .map(ApiResponse::json)
        .filter(order -> order.get("status").asString().equals(status))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No order " + status));
  }

  private int positionOf(long userId, String ticker) {
    for (JsonNode position : api.get(PORTFOLIO, userId).json().get("positions")) {
      if (position.get("ticker").asString().equals(ticker)) {
        return position.get("quantity").asInt();
      }
    }
    throw new AssertionError("No position for " + ticker);
  }

  private int orderCount() {
    return jdbc.sql("select count(*) from orders").query(Integer.class).single();
  }

  @Test
  void twoSimultaneousSalesNeverSellTheSameShares() throws Exception {
    long user = data.createUser();
    data.deposit(user, DEPOSIT);
    data.fill(user, BMA, OrderSide.BUY, BMA_HELD, BMA_PRICE);
    String sizing = "\"quantity\": 15";

    List<ApiResponse> responses =
        submitTogether(
            user,
            marketOrder(OrderSide.SELL, BMA, sizing),
            marketOrder(OrderSide.SELL, BMA, sizing),
            this::awaitTheTurnOrTheSecondRead);

    assertThat(positionOf(user, BMA_TICKER)).isEqualTo(5);
    assertThat(statuses(responses)).containsExactlyInAnyOrder("FILLED", "REJECTED");
    assertThat(withStatus(responses, "REJECTED").get("rejectionReason").asString())
        .isEqualTo("INSUFFICIENT_SHARES");
  }

  @Test
  void twoSimultaneousPurchasesNeverSpendTheSameCash() throws Exception {
    String sizing = "\"amount\": 600000";

    List<ApiResponse> responses =
        submitTogether(
            USER_ONE,
            marketOrder(OrderSide.BUY, PAMP, sizing),
            marketOrder(OrderSide.BUY, PAMP, sizing),
            this::awaitTheTurnOrTheSecondRead);

    assertThat(statuses(responses)).containsExactlyInAnyOrder("FILLED", "REJECTED");
    JsonNode filled = withStatus(responses, "FILLED");
    assertThat(filled.get("quantity").asInt()).isEqualTo(648);
    assertThat(filled.get("amount").decimalValue()).isEqualByComparingTo("599950.80");
    assertThat(withStatus(responses, "REJECTED").get("rejectionReason").asString())
        .isEqualTo("INSUFFICIENT_CASH");
  }

  /**
   * Waits for the turn only: without it, the uniqueness rule would still leave one order (the retry
   * below proves that net), but the second request would not wait.
   */
  @Test
  void twoSimultaneousRequestsWithTheSameIdentifierCreateOneOrder() throws Exception {
    String request = marketOrder(OrderSide.BUY, PAMP, "\"quantity\": 10");

    List<ApiResponse> responses = submitTogether(USER_ONE, request, request, this::awaitTheTurn);

    assertThat(responses).extracting(ApiResponse::status).containsExactly(201, 200);
    long orderId = responses.getFirst().json().get("id").asLong();
    assertThat(responses.getLast().json().get("id").asLong()).isEqualTo(orderId);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  @Test
  void retriesOnceWhenAnotherOrderTookTheIdentifierMeanwhile() throws Exception {
    // the first attempt skips the turn: the path the uniqueness rule guards (RFC 5.3)
    doReturn(true).doCallRealMethod().when(accounts).lockForTurn(anyLong(), any(Duration.class));
    String request = marketOrder(OrderSide.BUY, PAMP, "\"quantity\": 10");

    Future<ApiResponse> first = requests.submit(() -> submit(USER_ONE, request));
    pause.awaitReached(WAIT_LIMIT);
    ApiResponse second = responseOf(requests.submit(() -> submit(USER_ONE, request)));
    pause.release();
    ApiResponse retried = responseOf(first);

    assertThat(second.status()).isEqualTo(201);
    assertThat(retried.status()).isEqualTo(200);
    assertThat(retried.json().get("id").asLong()).isEqualTo(second.json().get("id").asLong());
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
    // the first attempt, the second request and the one retry of the first
    verify(accounts, times(3)).lockForTurn(eq(USER_ONE), any(Duration.class));
  }
}
