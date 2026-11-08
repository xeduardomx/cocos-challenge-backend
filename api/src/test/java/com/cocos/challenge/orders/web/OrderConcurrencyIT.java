package com.cocos.challenge.orders.web;

import static com.cocos.challenge.support.Concurrency.WAIT_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;

import com.cocos.challenge.account.AccountRepository;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.orders.submission.RequestFingerprint;
import com.cocos.challenge.orders.submission.SubmitOrderCommand;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.Concurrency;
import com.cocos.challenge.support.Concurrency.PausePoint;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.math.BigDecimal;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Two orders of the same user at the same time (RFC 5.2, 5.3 and 10.3). The first one waits right
 * after reading its account until the second one either waits for the turn of the user or reads its
 * account too: so, without the turn, both always read the same account.
 */
@IntegrationTest
class OrderConcurrencyIT {

  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final long USER_ONE = 1;
  private static final long BMA = 31;
  private static final long PAMP = 47;
  private static final String BMA_TICKER = "BMA";
  private static final BigDecimal BMA_PRICE = new BigDecimal("1502.80");
  private static final BigDecimal PAMP_PRICE = new BigDecimal("925.85");
  private static final int DEPOSIT = 1_000_000;
  private static final int BMA_HELD = 20;
  private static final int ORIGINAL_ORDERS = 11;
  private static final int SIMULTANEOUS_REQUESTS = 2;

  /** Only for the transaction that commits it: the insert skips the foreign key checks. */
  private static final String SKIP_FOREIGN_KEY_CHECKS =
      "set local session_replication_role = replica";

  private static final String INSERT_ORDER =
      """
      insert into orders (instrumentid, userid, side, type, size, price, status,
                          clientorderid, requestfingerprint)
      values (:instrumentId, :userId, :side, :type, :size, :price, :status,
              :clientOrderId, :requestFingerprint)
      returning id
      """;

  @LocalServerPort private int port;
  @Autowired private TestData data;
  @Autowired private Concurrency concurrency;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transaction;
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

  @AfterEach
  void releaseTheRequests() {
    pause.release();
    requests.shutdownNow();
  }

  private static String marketOrder(
      UUID clientOrderId, OrderSide side, long instrumentId, String sizing) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "%s", "type": "MARKET", %s }\
    """
        .formatted(clientOrderId, instrumentId, side.name(), sizing);
  }

  private static String marketOrder(OrderSide side, long instrumentId, String sizing) {
    return marketOrder(UUID.randomUUID(), side, instrumentId, sizing);
  }

  private ApiResponse submit(long userId, String json) {
    return api.post("/users/" + userId + "/orders", json);
  }

  /** Sends both requests so they cross: see the class comment. */
  private List<ApiResponse> submitTogether(long userId, String first, String second)
      throws Exception {
    Future<ApiResponse> firstResponse = requests.submit(() -> submit(userId, first));
    pause.awaitReached(WAIT_LIMIT);
    Future<ApiResponse> secondResponse = requests.submit(() -> submit(userId, second));
    concurrency.awaitBlockedOnLockOrSecondCall(pause, WAIT_LIMIT);
    pause.release();
    return List.of(responseOf(firstResponse), responseOf(secondResponse));
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
            marketOrder(OrderSide.SELL, BMA, sizing));

    assertThat(positionOf(user, BMA_TICKER)).isEqualTo(5);
    assertThat(statuses(responses)).containsExactlyInAnyOrder("FILLED", "REJECTED");
  }

  @Test
  void twoSimultaneousPurchasesNeverSpendTheSameCash() throws Exception {
    String sizing = "\"amount\": 600000";

    List<ApiResponse> responses =
        submitTogether(
            USER_ONE,
            marketOrder(OrderSide.BUY, PAMP, sizing),
            marketOrder(OrderSide.BUY, PAMP, sizing));

    assertThat(statuses(responses)).containsExactlyInAnyOrder("FILLED", "REJECTED");
    JsonNode filled = withStatus(responses, "FILLED");
    assertThat(filled.get("quantity").asInt()).isEqualTo(648);
    assertThat(filled.get("amount").decimalValue()).isEqualByComparingTo("599950.80");
    assertThat(withStatus(responses, "REJECTED").get("rejectionReason").asString())
        .isEqualTo("INSUFFICIENT_CASH");
  }

  @Test
  void twoSimultaneousRequestsWithTheSameIdentifierCreateOneOrder() throws Exception {
    String request = marketOrder(OrderSide.BUY, PAMP, "\"quantity\": 10");

    List<ApiResponse> responses = submitTogether(USER_ONE, request, request);

    assertThat(responses).extracting(ApiResponse::status).containsExactlyInAnyOrder(201, 200);
    long orderId = responses.getFirst().json().get("id").asLong();
    assertThat(responses.getLast().json().get("id").asLong()).isEqualTo(orderId);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  @Test
  void retriesOnceWhenAnotherOrderTookTheIdentifierMeanwhile() throws Exception {
    int quantity = 10;
    SubmitOrderCommand command =
        new SubmitOrderCommand(
            USER_ONE,
            UUID.randomUUID(),
            PAMP,
            OrderSide.BUY,
            OrderType.MARKET,
            quantity,
            null,
            null);
    String request =
        marketOrder(command.clientOrderId(), OrderSide.BUY, PAMP, "\"quantity\": " + quantity);

    Future<ApiResponse> submission = requests.submit(() -> submit(USER_ONE, request));
    pause.awaitReached(WAIT_LIMIT);
    long otherOrderId = recordTheSameOrderElsewhere(command);
    pause.release();
    ApiResponse response = responseOf(submission);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("id").asLong()).isEqualTo(otherOrderId);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  /**
   * Records and commits, in a transaction of its own, the order the paused submission is about to
   * record: same user, identifier and fingerprint. The paused submission holds the turn, a lock on
   * the row of the user, and the foreign key check of any other insert of an order of that user
   * waits for that lock: with the turn, nobody else can take the identifier meanwhile. To reach the
   * uniqueness rule all the same, the safety net of RFC 5.3, this insert skips the foreign key
   * checks.
   */
  private long recordTheSameOrderElsewhere(SubmitOrderCommand command) {
    Long id =
        transaction.execute(status -> {
          jdbc.sql(SKIP_FOREIGN_KEY_CHECKS).update();
          return jdbc.sql(INSERT_ORDER)
              .param("instrumentId", command.instrumentId())
              .param("userId", command.userId())
              .param("side", command.side().name())
              .param("type", command.type().name())
              .param("size", command.quantity())
              .param("price", PAMP_PRICE)
              .param("status", OrderStatus.FILLED.name())
              .param("clientOrderId", command.clientOrderId())
              .param("requestFingerprint", RequestFingerprint.of(command).value())
              .query(Long.class)
              .single();
        });
    assertThat(id).isNotNull();
    return id;
  }
}
