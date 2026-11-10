package com.cocos.challenge.shared.web;

import static com.cocos.challenge.support.OrderRequests.marketBuy;
import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The metrics for Prometheus (RFC 8.2, case 4 of GEN). The counters are shared by all the tests of
 * the run, so each test compares the scrape before and after its action.
 */
@IntegrationTest
class MetricsIT {

  private static final String SCRAPE = "/actuator/prometheus";
  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final String ORDERS_OF_USER_ONE = "/users/1/orders";
  private static final String CANCEL_PENDING_ORDER = ORDERS_OF_USER_ONE + "/7/cancel";
  private static final long USER_ONE = 1;
  private static final String TEN_SHARES = "\"quantity\": 10";
  private static final String MORE_THAN_THE_CASH = "\"amount\": 700000";

  private static final String FILLED_PURCHASES =
      "orders_recorded_total{reason=\"none\",side=\"BUY\",status=\"FILLED\",type=\"MARKET\"}";
  private static final String PURCHASES_WITHOUT_CASH =
      "orders_recorded_total{reason=\"INSUFFICIENT_CASH\",side=\"BUY\",status=\"REJECTED\","
          + "type=\"MARKET\"}";
  private static final String REPLAYED = "orders_replayed_total";
  private static final String CANCELLED = "orders_cancelled_total";
  private static final String TURN_WAITS = "orders_turn_wait_seconds_count";
  private static final String TURN_WAIT_BUCKET = "orders_turn_wait_seconds_bucket{";
  private static final String HISTOGRAM_BUCKET = "http_server_requests_seconds_bucket{";
  private static final String PORTFOLIO_ROUTE = "uri=\"/users/{userId}/portfolio\"";

  @LocalServerPort private int port;

  private ApiClient api;

  @BeforeEach
  void createClient() {
    api = new ApiClient(port);
  }

  private List<String> scrape() {
    ApiResponse response = api.getText(SCRAPE);
    assertThat(response.status()).isEqualTo(200);
    return response.body().lines().toList();
  }

  /** The value of a series. One that does not exist fails the test: it never counts as 0. */
  private double valueOf(String series) {
    String prefix = series + " ";
    return scrape().stream()
        .filter(line -> line.startsWith(prefix))
        .map(line -> Double.parseDouble(line.substring(prefix.length())))
        .findFirst()
        .orElseThrow(() -> new AssertionError("The scrape has no series " + series));
  }

  private ApiResponse submit(String json) {
    return api.post(ORDERS_OF_USER_ONE, json);
  }

  @Test
  void aFilledPurchaseAddsOneToItsSeries() {
    double before = valueOf(FILLED_PURCHASES);

    ApiResponse response = submit(marketBuy(UUID.randomUUID(), TEN_SHARES));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.json().get("status").asString()).isEqualTo("FILLED");
    assertThat(valueOf(FILLED_PURCHASES)).isEqualTo(before + 1);
  }

  @Test
  void aRejectedPurchaseAddsOneWithItsReason() {
    double before = valueOf(PURCHASES_WITHOUT_CASH);

    ApiResponse response = submit(marketBuy(UUID.randomUUID(), MORE_THAN_THE_CASH));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.json().get("status").asString()).isEqualTo("REJECTED");
    assertThat(valueOf(PURCHASES_WITHOUT_CASH)).isEqualTo(before + 1);
  }

  @Test
  void aRetryAddsOneToTheReplaysAndNothingToTheRecorded() {
    String purchase = marketBuy(UUID.randomUUID(), TEN_SHARES);
    assertThat(submit(purchase).status()).isEqualTo(201);
    double replayedBefore = valueOf(REPLAYED);
    double recordedBefore = valueOf(FILLED_PURCHASES);

    assertThat(submit(purchase).status()).isEqualTo(200);

    assertThat(valueOf(REPLAYED)).isEqualTo(replayedBefore + 1);
    assertThat(valueOf(FILLED_PURCHASES)).isEqualTo(recordedBefore);
  }

  @Test
  void aCancellationAddsOne() {
    double before = valueOf(CANCELLED);

    assertThat(api.post(CANCEL_PENDING_ORDER, "").status()).isEqualTo(200);

    assertThat(valueOf(CANCELLED)).isEqualTo(before + 1);
  }

  @Test
  void aSubmissionMeasuresTheWaitForItsTurn() {
    double before = valueOf(TURN_WAITS);

    assertThat(submit(marketBuy(UUID.randomUUID(), TEN_SHARES)).status()).isEqualTo(201);

    assertThat(valueOf(TURN_WAITS)).isGreaterThan(before);
  }

  @Test
  void aRequestGoesToTheHistogramOfItsRoute() {
    assertThat(api.get(PORTFOLIO, USER_ONE).status()).isEqualTo(200);

    assertThat(scrape())
        .anyMatch(line -> line.startsWith(HISTOGRAM_BUCKET) && line.contains(PORTFOLIO_ROUTE));
  }

  @Test
  void theWaitForTheTurnHasBucketsForThePercentile() {
    assertThat(submit(marketBuy(UUID.randomUUID(), TEN_SHARES)).status()).isEqualTo(201);

    assertThat(scrape()).anyMatch(line -> line.startsWith(TURN_WAIT_BUCKET));
  }
}
