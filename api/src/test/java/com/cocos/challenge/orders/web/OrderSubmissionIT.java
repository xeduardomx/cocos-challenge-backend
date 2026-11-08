package com.cocos.challenge.orders.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OrderSubmissionIT {

  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final long USER_ONE = 1;
  private static final long USER_TWO = 2;
  private static final long PGR = 3;
  private static final long BMA = 31;
  private static final long METR = 54;
  private static final long PAMP = 47;
  private static final int ORIGINAL_ORDERS = 11;
  private static final String SOME_CLIENT_ORDER_ID = "3f2b8c1e-9d4a-4c7b-8e21-5a6f0b9c2d13";
  private static final Duration FAST = Duration.ofSeconds(1);
  private static final String VALIDATION_ERROR = "/problems/validation-error";
  private static final String INVALID_ORDER = "/problems/invalid-order";

  @LocalServerPort private int port;
  @Autowired private TestData data;
  @Autowired private JdbcClient jdbc;

  private ApiClient api;

  @BeforeEach
  void createClient() {
    api = new ApiClient(port);
  }

  private static String order(UUID clientOrderId, long instrumentId, String rest) {
    return """
    { "clientOrderId": "%s", "instrumentId": %d, %s }\
    """
        .formatted(clientOrderId, instrumentId, rest);
  }

  private static String buy(long instrumentId, String sizing) {
    return order(
        UUID.randomUUID(), instrumentId, "\"side\": \"BUY\", \"type\": \"MARKET\", " + sizing);
  }

  private static String sell(long instrumentId, String sizing) {
    return order(
        UUID.randomUUID(), instrumentId, "\"side\": \"SELL\", \"type\": \"MARKET\", " + sizing);
  }

  private static String buyLimit(long instrumentId, String sizing, String limit) {
    return order(
        UUID.randomUUID(),
        instrumentId,
        "\"side\": \"BUY\", \"type\": \"LIMIT\", " + sizing + ", \"price\": " + limit);
  }

  private ApiResponse submit(long userId, String json) {
    return api.post("/users/" + userId + "/orders", json);
  }

  private int orderCount() {
    return jdbc.sql("select count(*) from orders").query(Integer.class).single();
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

  @Test
  void filledMarketPurchaseReturnsTheOrderInTheDisplayZone() {
    String json =
        order(
            UUID.fromString(SOME_CLIENT_ORDER_ID),
            PAMP,
            "\"side\": \"BUY\", \"type\": \"MARKET\", \"quantity\": 10");

    ApiResponse response = submit(USER_ONE, json);

    assertThat(response.status()).isEqualTo(201);
    JsonNode body = response.json();
    assertThat(body.get("id").asLong()).isEqualTo(12);
    assertThat(body.get("clientOrderId").asString()).isEqualTo(SOME_CLIENT_ORDER_ID);
    assertThat(body.get("instrumentId").asLong()).isEqualTo(PAMP);
    assertThat(body.get("ticker").asString()).isEqualTo("PAMP");
    assertThat(body.get("side").asString()).isEqualTo("BUY");
    assertThat(body.get("type").asString()).isEqualTo("MARKET");
    assertThat(body.get("quantity").asInt()).isEqualTo(10);
    assertThat(body.get("price").decimalValue()).isEqualByComparingTo("925.85");
    assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("9258.50");
    assertThat(body.get("status").asString()).isEqualTo("FILLED");
    assertThat(body.get("rejectionReason").isNull()).isTrue();
    assertThat(body.get("rejectionMessage").isNull()).isTrue();
    assertThat(body.get("datetime").asString()).endsWith("-03:00");
  }

  @Test
  void aFilledPurchaseChangesThePortfolio() {
    submit(USER_ONE, buy(PAMP, "\"quantity\": 10"));

    JsonNode portfolio = portfolioOf(USER_ONE);
    JsonNode pamp = position(portfolio, "PAMP");
    assertThat(pamp.get("quantity").asInt()).isEqualTo(50);
    assertThat(pamp.get("averagePrice").decimalValue()).isEqualByComparingTo("929.17");
    assertThat(pamp.get("totalReturnPercent").decimalValue()).isEqualByComparingTo("-0.36");
    assertThat(portfolio.get("cash").get("available").decimalValue())
        .isEqualByComparingTo("618241.50");
    assertThat(portfolio.get("totalValue").decimalValue()).isEqualByComparingTo("889756.00");
  }

  @Test
  void buyingBackAShortPositionLeavesItsAveragePrice() {
    submit(USER_ONE, buy(BMA, "\"quantity\": 15"));

    JsonNode bma = position(portfolioOf(USER_ONE), "BMA");
    assertThat(bma.get("quantity").asInt()).isEqualTo(5);
    assertThat(bma.get("averagePrice").decimalValue()).isEqualByComparingTo("1502.80");
    assertThat(bma.get("totalReturnPercent").decimalValue()).isEqualByComparingTo("0.00");
  }

  @Test
  void aLimitOrderStaysNewAndReservesCash() {
    ApiResponse response = submit(USER_ONE, buyLimit(METR, "\"quantity\": 10", "10000"));

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.json().get("status").asString()).isEqualTo("NEW");
    assertThat(portfolioOf(USER_ONE).get("cash").get("available").decimalValue())
        .isEqualByComparingTo("527500.00");
  }

  @Test
  void anAmountBecomesTheWholeSharesItBuys() {
    JsonNode body = submit(USER_ONE, buy(PAMP, "\"amount\": 10000")).json();

    assertThat(body.get("quantity").asInt()).isEqualTo(10);
    assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("9258.50");
  }

  @Test
  void anAmountThatCoversExactlyTheSharesBuysThem() {
    JsonNode body = submit(USER_ONE, buy(PAMP, "\"amount\": 9258.50")).json();

    assertThat(body.get("quantity").asInt()).isEqualTo(10);
  }

  @Test
  void anAmountWithALimitPriceUsesTheLimit() {
    JsonNode body = submit(USER_ONE, buyLimit(PAMP, "\"amount\": 10000", "900")).json();

    assertThat(body.get("quantity").asInt()).isEqualTo(11);
    assertThat(body.get("status").asString()).isEqualTo("NEW");
    assertThat(body.get("price").decimalValue()).isEqualByComparingTo("900");
    assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("9900.00");
  }

  @Test
  void aSaleByAmountIsFilled() {
    JsonNode body = submit(USER_ONE, sell(PAMP, "\"amount\": 10000")).json();

    assertThat(body.get("quantity").asInt()).isEqualTo(10);
    assertThat(body.get("status").asString()).isEqualTo("FILLED");
  }

  @Test
  void recordsAPurchaseThePesosDoNotCoverAsRejectedWithItsReason() {
    ApiResponse response = submit(USER_ONE, buy(PAMP, "\"amount\": 700000"));

    assertThat(response.status()).isEqualTo(201);
    JsonNode body = response.json();
    assertThat(body.get("quantity").asInt()).isEqualTo(756);
    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_CASH");
    assertThat(body.get("rejectionMessage").asString())
        .isEqualTo("No alcanzan los pesos disponibles para esta orden.");
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  @Test
  void rejectsAPurchaseThatNeedsMoreThanTheAvailableCash() {
    JsonNode body = submit(USER_ONE, buy(PAMP, "\"amount\": 630000")).json();

    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_CASH");
  }

  @Test
  void rejectsALimitPurchaseThatNeedsMoreThanTheAvailableCash() {
    JsonNode body = submit(USER_ONE, buyLimit(METR, "\"quantity\": 100", "10000")).json();

    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_CASH");
  }

  @Test
  void rejectsASaleOfMoreSharesThanTheUserHolds() {
    JsonNode body = submit(USER_ONE, sell(PAMP, "\"quantity\": 50")).json();

    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_SHARES");
    assertThat(body.get("rejectionMessage").asString())
        .isEqualTo("No alcanzan las acciones disponibles para esta venta.");
  }

  @Test
  void rejectsASaleOfSharesAPendingSaleAlreadyReserves() {
    data.pending(USER_ONE, PAMP, OrderSide.SELL, 30, new BigDecimal("1000"));

    JsonNode body = submit(USER_ONE, sell(PAMP, "\"quantity\": 20")).json();

    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_SHARES");
  }

  @Test
  void rejectsAMarketOrderOnAnInstrumentWithoutAPrice() {
    ApiResponse response = submit(USER_ONE, buy(PGR, "\"quantity\": 10"));

    assertThat(response.status()).isEqualTo(201);
    JsonNode body = response.json();
    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("NO_MARKET_PRICE");
    assertThat(body.get("rejectionMessage").asString()).isEqualTo("El activo no cotiza.");
    assertThat(body.get("price").isNull()).isTrue();
    assertThat(body.get("amount").isNull()).isTrue();
  }

  @Test
  void rejectsALimitOrderOnAnInstrumentWithoutAPriceToo() {
    JsonNode body = submit(USER_ONE, buyLimit(PGR, "\"quantity\": 10", "100")).json();

    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("NO_MARKET_PRICE");
    assertThat(body.get("price").decimalValue()).isEqualByComparingTo("100");
  }

  @Test
  void rejectsAPurchaseOfAUserWithoutCash() {
    JsonNode body = submit(USER_TWO, buy(PAMP, "\"quantity\": 1")).json();

    assertThat(body.get("status").asString()).isEqualTo("REJECTED");
    assertThat(body.get("rejectionReason").asString()).isEqualTo("INSUFFICIENT_CASH");
  }

  static Stream<Arguments> invalidRequests() {
    String id = "\"clientOrderId\": \"" + SOME_CLIENT_ORDER_ID + "\", ";
    String pamp = "\"instrumentId\": 47, ";
    String market = "\"side\": \"BUY\", \"type\": \"MARKET\", ";
    return Stream.of(
        Arguments.of(
            "missing clientOrderId", "{ " + pamp + market + "\"quantity\": 10 }", VALIDATION_ERROR),
        Arguments.of(
            "unknown side",
            "{ " + id + pamp + "\"side\": \"COMPRA\", \"type\": \"MARKET\", \"quantity\": 10 }",
            VALIDATION_ERROR),
        Arguments.of(
            "side CASH_IN",
            "{ " + id + pamp + "\"side\": \"CASH_IN\", \"type\": \"MARKET\", \"quantity\": 10 }",
            VALIDATION_ERROR),
        Arguments.of(
            "fractional quantity",
            "{ " + id + pamp + market + "\"quantity\": 2.5 }",
            VALIDATION_ERROR),
        Arguments.of(
            "zero quantity", "{ " + id + pamp + market + "\"quantity\": 0 }", VALIDATION_ERROR),
        Arguments.of(
            "negative amount", "{ " + id + pamp + market + "\"amount\": -1 }", VALIDATION_ERROR),
        Arguments.of(
            "zero limit price",
            "{ "
                + id
                + pamp
                + "\"side\": \"BUY\", \"type\": \"LIMIT\", \"quantity\": 10, \"price\": 0 }",
            VALIDATION_ERROR),
        Arguments.of(
            "price with three decimals",
            "{ "
                + id
                + pamp
                + "\"side\": \"BUY\", \"type\": \"LIMIT\", \"quantity\": 10, \"price\": 229.555 }",
            VALIDATION_ERROR),
        Arguments.of(
            "quantity and amount",
            "{ " + id + pamp + market + "\"quantity\": 10, \"amount\": 10000 }",
            VALIDATION_ERROR),
        Arguments.of(
            "neither quantity nor amount",
            "{ " + id + pamp + market.replaceAll(", $", "") + " }",
            VALIDATION_ERROR),
        Arguments.of(
            "limit without price",
            "{ " + id + pamp + "\"side\": \"BUY\", \"type\": \"LIMIT\", \"quantity\": 10 }",
            VALIDATION_ERROR),
        Arguments.of(
            "market with price",
            "{ " + id + pamp + market + "\"quantity\": 10, \"price\": 900 }",
            VALIDATION_ERROR),
        Arguments.of(
            "unknown field",
            "{ " + id + pamp + market + "\"quantity\": 10, \"prise\": 900 }",
            VALIDATION_ERROR),
        Arguments.of(
            "instrument that does not exist",
            "{ " + id + "\"instrumentId\": 999, " + market + "\"quantity\": 10 }",
            INVALID_ORDER),
        Arguments.of(
            "the currency",
            "{ " + id + "\"instrumentId\": 66, " + market + "\"quantity\": 10 }",
            INVALID_ORDER),
        Arguments.of(
            "amount that buys no share",
            "{ " + id + pamp + market + "\"amount\": 500 }",
            INVALID_ORDER),
        Arguments.of(
            "amount of an instrument without price",
            "{ " + id + "\"instrumentId\": 3, " + market + "\"amount\": 10000 }",
            INVALID_ORDER));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidRequests")
  void refusesAnInvalidRequestAndRecordsNothing(String name, String json, String problemType) {
    ApiResponse response = submit(USER_ONE, json);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(problemType);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS);
  }

  @Test
  void explainsAValidationErrorInSpanish() {
    ApiResponse response =
        submit(
            USER_ONE,
            "{ \"instrumentId\": 47, \"side\": \"BUY\", \"type\": \"MARKET\", \"quantity\": 10 }");

    assertThat(response.json().get("errors").get(0).get("field").asString())
        .isEqualTo("clientOrderId");
    assertThat(response.json().get("errors").get(0).get("message").asString())
        .isEqualTo("Falta el identificador de la orden (clientOrderId).");
  }

  @Test
  void explainsAnInvalidOrderInSpanish() {
    ApiResponse missing = submit(USER_ONE, buy(999, "\"quantity\": 10"));
    ApiResponse tooSmall = submit(USER_ONE, buy(PAMP, "\"amount\": 500"));

    assertThat(missing.json().get("detail").asString()).isEqualTo("No existe el activo 999.");
    assertThat(tooSmall.json().get("detail").asString())
        .isEqualTo("Con $500 no alcanza para una acción de PAMP ($925,85).");
  }

  @Test
  void answersNotFoundForAUserThatDoesNotExist() {
    ApiResponse response = submit(99, buy(PAMP, "\"quantity\": 10"));

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.json().get("type").asString()).isEqualTo("/problems/user-not-found");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "\"quantity\": 3000000000",
        "\"amount\": 1e999999999",
      })
  void refusesANumberThatDoesNotFitFastWithAMarketOrder(String sizing) {
    ApiResponse response =
        assertTimeoutPreemptively(FAST, () -> submit(USER_ONE, buy(PAMP, sizing)));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"1e400", "1e2147483647"})
  void refusesAHugeLimitPriceFast(String price) {
    ApiResponse response =
        assertTimeoutPreemptively(
            FAST, () -> submit(USER_ONE, buyLimit(PAMP, "\"quantity\": 10", price)));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"10000", "10000.00", "1E+4"})
  void answersARetryWithTheOriginalOrderAndRecordsNothingMore(String retriedAmount) {
    UUID clientOrderId = UUID.randomUUID();
    String rest = "\"side\": \"BUY\", \"type\": \"MARKET\", \"amount\": ";
    ApiResponse first = submit(USER_ONE, order(clientOrderId, PAMP, rest + "10000"));

    ApiResponse retry = submit(USER_ONE, order(clientOrderId, PAMP, rest + retriedAmount));

    assertThat(first.status()).isEqualTo(201);
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.json()).isEqualTo(first.json());
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  @Test
  void answersARetryOfARejectedOrderWithoutEvaluatingItAgain() {
    UUID clientOrderId = UUID.randomUUID();
    String json =
        order(clientOrderId, PAMP, "\"side\": \"BUY\", \"type\": \"MARKET\", \"amount\": 700000");
    ApiResponse first = submit(USER_ONE, json);

    ApiResponse retry = submit(USER_ONE, json);

    assertThat(first.json().get("status").asString()).isEqualTo("REJECTED");
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.json()).isEqualTo(first.json());
  }

  @Test
  void refusesAClientOrderIdUsedForAnotherOrder() {
    UUID clientOrderId = UUID.randomUUID();
    String rest = "\"side\": \"BUY\", \"type\": \"MARKET\", \"quantity\": ";
    submit(USER_ONE, order(clientOrderId, PAMP, rest + "10"));

    ApiResponse response = submit(USER_ONE, order(clientOrderId, PAMP, rest + "11"));

    assertThat(response.status()).isEqualTo(422);
    assertThat(response.json().get("type").asString())
        .isEqualTo("/problems/client-order-id-reused");
    assertThat(response.json().get("detail").asString())
        .isEqualTo(
            "Ese identificador ya se usó en otra orden. Para una orden nueva, generá uno nuevo.");
    assertThat(orderCount()).isEqualTo(ORIGINAL_ORDERS + 1);
  }

  @Test
  void letsTwoUsersUseTheSameClientOrderId() {
    UUID clientOrderId = UUID.randomUUID();
    String json =
        order(clientOrderId, PAMP, "\"side\": \"BUY\", \"type\": \"MARKET\", \"quantity\": 1");

    assertThat(submit(USER_ONE, json).status()).isEqualTo(201);
    assertThat(submit(USER_TWO, json).status()).isEqualTo(201);
  }

  @Test
  void letsTheClientFixARefusedRequestAndSendItAgainWithTheSameClientOrderId() {
    UUID clientOrderId = UUID.randomUUID();
    String rest = "\"side\": \"BUY\", \"type\": \"MARKET\", \"quantity\": ";

    ApiResponse refused = submit(USER_ONE, order(clientOrderId, PAMP, rest + "0"));
    ApiResponse fixed = submit(USER_ONE, order(clientOrderId, PAMP, rest + "10"));

    assertThat(refused.status()).isEqualTo(400);
    assertThat(fixed.status()).isEqualTo(201);
  }
}
