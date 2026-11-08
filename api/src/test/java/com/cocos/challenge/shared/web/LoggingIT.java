package com.cocos.challenge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.LogLines;
import com.cocos.challenge.support.TestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * What the API really writes to its logs, read as JSON (RFC 8.1, case 5 of GEN). Each test sends
 * its own request id, so a line is found by that id. A line that is not there fails the test.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoggingIT {

  private static final long USER_ONE = 1;
  private static final long UNKNOWN_USER = 99;
  private static final long PAMP = 47;
  private static final long METR = 54;
  private static final long ORIGINAL_PENDING_ORDER = 7;
  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final String ORDERS_OF_USER_ONE = "/users/1/orders";
  private static final String CANCEL_ORDER_OF_USER_ONE = ORDERS_OF_USER_ONE + "/%d/cancel";
  private static final String TEN_SHARES = "\"quantity\": 10";
  private static final String MORE_THAN_THE_CASH = "\"amount\": 700000";
  private static final String ORDER_ID_FIELD = "orderId";
  private static final String CLIENT_ORDER_ID_FIELD = "clientOrderId";
  private static final String STATUS_FIELD = "status";
  private static final String REASON_FIELD = "rejectionReason";
  private static final String ABOUT_BLANK = "about:blank";

  @LocalServerPort private int port;

  private String requestId;
  private ApiClient api;

  @BeforeEach
  void createClient() {
    requestId = UUID.randomUUID().toString();
    api = new ApiClient(port, withRequestId(new HttpHeaders()));
  }

  private HttpHeaders withRequestId(HttpHeaders headers) {
    headers.set(RequestIds.HEADER, requestId);
    return headers;
  }

  private static String marketBuy(UUID clientOrderId, String sizing) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "MARKET", %s }\
    """
        .formatted(clientOrderId, PAMP, sizing);
  }

  private static String limitBuy(UUID clientOrderId) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, "side": "BUY", "type": "LIMIT", \
    "quantity": 10, "price": 10000 }\
    """
        .formatted(clientOrderId, METR);
  }

  private ApiResponse cancel(long orderId) {
    return api.post(CANCEL_ORDER_OF_USER_ONE.formatted(orderId), "");
  }

  /** The lines of this test about an order. */
  private List<JsonNode> orderLines(CapturedOutput output) {
    return LogLines.ofRequest(output, requestId).stream()
        .filter(line -> line.has(ORDER_ID_FIELD))
        .toList();
  }

  private void assertOneWarning(
      CapturedOutput output, ApiResponse response, int code, String problemType) {
    assertThat(response.status()).isEqualTo(code);
    assertThat(LogLines.errorsOfRequest(output, requestId))
        .singleElement()
        .satisfies(line -> {
          assertThat(LogLines.level(line)).isEqualTo("WARN");
          assertThat(line.get("httpStatus").asInt()).isEqualTo(code);
          assertThat(line.get("problemType").asString()).isEqualTo(problemType);
          assertThat(LogLines.hasStackTrace(line)).isFalse();
        });
  }

  @Test
  void writesJsonLinesWithTheRequestAndTheOrderAndNeverAnEmail(CapturedOutput output) {
    UUID clientOrderId = UUID.randomUUID();

    ApiResponse order = api.post(ORDERS_OF_USER_ONE, marketBuy(clientOrderId, TEN_SHARES));
    ApiResponse portfolio = api.get(PORTFOLIO, USER_ONE);
    ApiResponse listing = api.get(ORDERS_OF_USER_ONE);

    assertThat(order.status()).isEqualTo(201);
    assertThat(portfolio.status()).isEqualTo(200);
    assertThat(listing.status()).isEqualTo(200);
    assertThat(orderLines(output))
        .singleElement()
        .satisfies(line -> {
          assertThat(line.get(RequestIds.MDC_KEY).asString()).isEqualTo(requestId);
          assertThat(line.get(ORDER_ID_FIELD).asString())
              .isEqualTo(order.json().get("id").asString());
          assertThat(line.get("userId").asString()).isEqualTo(String.valueOf(USER_ONE));
          assertThat(line.get(CLIENT_ORDER_ID_FIELD).asString())
              .isEqualTo(clientOrderId.toString());
          assertThat(line.get("side").asString()).isEqualTo("BUY");
          assertThat(line.get("type").asString()).isEqualTo("MARKET");
          assertThat(line.get(STATUS_FIELD).asString()).isEqualTo("FILLED");
          assertThat(line.has(REASON_FIELD)).isFalse();
        });
    assertThat(output.getOut()).doesNotContain(TestData.EMAIL_DOMAIN);
    assertThat(portfolio.body()).doesNotContain(TestData.EMAIL_DOMAIN);
    assertThat(listing.body()).doesNotContain(TestData.EMAIL_DOMAIN);
  }

  @Test
  void writesTheReasonOfARejectedOrder(CapturedOutput output) {
    ApiResponse order =
        api.post(ORDERS_OF_USER_ONE, marketBuy(UUID.randomUUID(), MORE_THAN_THE_CASH));

    assertThat(order.json().get(STATUS_FIELD).asString()).isEqualTo("REJECTED");
    assertThat(orderLines(output))
        .singleElement()
        .satisfies(line -> {
          assertThat(line.get(STATUS_FIELD).asString()).isEqualTo("REJECTED");
          assertThat(line.get(REASON_FIELD).asString()).isEqualTo("INSUFFICIENT_CASH");
        });
  }

  @Test
  void tellsARetryFromANewOrder(CapturedOutput output) {
    String purchase = marketBuy(UUID.randomUUID(), TEN_SHARES);

    assertThat(api.post(ORDERS_OF_USER_ONE, purchase).status()).isEqualTo(201);
    assertThat(api.post(ORDERS_OF_USER_ONE, purchase).status()).isEqualTo(200);

    assertThat(orderLines(output))
        .extracting(line -> line.get("message").asString())
        .containsExactly("Order processed", "Order replayed");
  }

  @Test
  void writesACancellationWithItsOrderAndItsStatus(CapturedOutput output) {
    ApiResponse pending = api.post(ORDERS_OF_USER_ONE, limitBuy(UUID.randomUUID()));
    String orderId = pending.json().get("id").asString();

    ApiResponse cancelled = cancel(pending.json().get("id").asLong());

    assertThat(cancelled.status()).isEqualTo(200);
    assertThat(orderLines(output))
        .last()
        .satisfies(line -> {
          assertThat(line.get("message").asString()).isEqualTo("Order cancelled");
          assertThat(line.get(ORDER_ID_FIELD).asString()).isEqualTo(orderId);
          assertThat(line.get(STATUS_FIELD).asString()).isEqualTo("CANCELLED");
        });
  }

  @Test
  void cancellingAnOriginalOrderLeavesOutWhatItDoesNotHave(CapturedOutput output) {
    ApiResponse cancelled = cancel(ORIGINAL_PENDING_ORDER);

    assertThat(cancelled.status()).isEqualTo(200);
    assertThat(orderLines(output))
        .singleElement()
        .satisfies(line -> {
          assertThat(line.get(ORDER_ID_FIELD).asString())
              .isEqualTo(String.valueOf(ORIGINAL_PENDING_ORDER));
          assertThat(line.get(STATUS_FIELD).asString()).isEqualTo("CANCELLED");
          assertThat(line.has(CLIENT_ORDER_ID_FIELD)).isFalse();
          assertThat(line.has(REASON_FIELD)).isFalse();
        });
  }

  @Test
  void anErrorOfTheApiIsOneWarningWithoutAStackTrace(CapturedOutput output) {
    assertOneWarning(output, api.get(PORTFOLIO, UNKNOWN_USER), 404, "/problems/user-not-found");
  }

  @Test
  void aValidationErrorIsOneWarningToo(CapturedOutput output) {
    assertOneWarning(output, api.post(ORDERS_OF_USER_ONE, "{}"), 400, "/problems/validation-error");
  }

  @Test
  void anUnknownAddressIsOneWarningWithAboutBlank(CapturedOutput output) {
    assertOneWarning(output, api.get("/nope"), 404, ABOUT_BLANK);
  }

  @Test
  void aMethodThatIsNotAllowedIsOneWarningWithAboutBlank(CapturedOutput output) {
    assertOneWarning(output, api.post("/users/1/portfolio", "{}"), 405, ABOUT_BLANK);
  }

  @Test
  void aResponseFormatTheApiCannotGiveIsOneWarningWithAboutBlank(CapturedOutput output) {
    HttpHeaders headers = withRequestId(new HttpHeaders());
    headers.setAccept(List.of(MediaType.APPLICATION_XML));

    ApiResponse response = new ApiClient(port, headers).get(PORTFOLIO, USER_ONE);

    assertOneWarning(output, response, 406, ABOUT_BLANK);
  }

  @Test
  void aBodyFormatTheApiDoesNotTakeIsOneWarningWithAboutBlank(CapturedOutput output) {
    ApiResponse response = api.post(ORDERS_OF_USER_ONE, MediaType.TEXT_PLAIN, "no es JSON");

    assertOneWarning(output, response, 415, ABOUT_BLANK);
  }

  @Test
  void anUnexpectedErrorIsOneErrorWithItsStackTrace(CapturedOutput output) {
    ApiResponse response = api.get("/probe/unexpected");

    assertThat(response.status()).isEqualTo(500);
    assertThat(LogLines.errorsOfRequest(output, requestId))
        .singleElement()
        .satisfies(line -> {
          assertThat(LogLines.level(line)).isEqualTo("ERROR");
          assertThat(line.get("httpStatus").asInt()).isEqualTo(500);
          assertThat(line.get("problemType").asString()).isEqualTo("/problems/internal-error");
          assertThat(LogLines.stackTrace(line)).contains(IllegalStateException.class.getName());
        });
  }
}
