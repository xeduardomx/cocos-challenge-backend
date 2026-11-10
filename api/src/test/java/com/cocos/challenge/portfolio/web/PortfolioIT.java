package com.cocos.challenge.portfolio.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PortfolioIT {

  private static final String PORTFOLIO = "/users/{userId}/portfolio";
  private static final long BBAR = 22;
  private static final long DOME = 12;
  private static final long PGR = 3;
  private static final long PAMP = 47;

  @LocalServerPort private int port;
  @Autowired private TestData data;

  private ApiClient api;

  @BeforeEach
  void createClient() {
    api = new ApiClient(port);
  }

  private JsonNode portfolioOf(long userId) {
    ApiResponse response = api.get(PORTFOLIO, userId);
    assertThat(response.status()).isEqualTo(200);
    return response.json();
  }

  private static JsonNode position(JsonNode portfolio, String ticker) {
    for (JsonNode position : portfolio.get("positions")) {
      if (position.get("ticker").asString().equals(ticker)) {
        return position;
      }
    }
    throw new AssertionError("No position for " + ticker);
  }

  private long userWithPosition(long instrumentId, int size, String price) {
    long userId = data.createUser();
    data.deposit(userId, 100000);
    data.fill(userId, instrumentId, OrderSide.BUY, size, new BigDecimal(price));
    return userId;
  }

  @Test
  void showsTheCompletePortfolioOfUserOne() {
    JsonNode portfolio = portfolioOf(1);

    assertThat(portfolio.get("userId").asLong()).isEqualTo(1);
    assertThat(portfolio.get("totalValue").decimalValue()).isEqualByComparingTo("889756.00");
    JsonNode cash = portfolio.get("cash");
    assertThat(cash.get("balance").decimalValue()).isEqualByComparingTo("753000.00");
    assertThat(cash.get("reserved").decimalValue()).isEqualByComparingTo("125500.00");
    assertThat(cash.get("available").decimalValue()).isEqualByComparingTo("627500.00");

    assertThat(portfolio.get("positions")).hasSize(3);
    assertThat(portfolio.get("positions").get(0).get("ticker").asString()).isEqualTo("BMA");
    assertThat(portfolio.get("positions").get(1).get("ticker").asString()).isEqualTo("METR");
    assertThat(portfolio.get("positions").get(2).get("ticker").asString()).isEqualTo("PAMP");

    JsonNode bma = position(portfolio, "BMA");
    assertThat(bma.get("instrumentId").asLong()).isEqualTo(31);
    assertThat(bma.get("name").asString()).isEqualTo("Banco Macro S.A.");
    assertThat(bma.get("quantity").asLong()).isEqualTo(-10);
    assertThat(bma.get("reservedQuantity").asLong()).isZero();
    assertThat(bma.get("lastPrice").decimalValue()).isEqualByComparingTo("1502.80");
    assertThat(bma.get("marketValue").decimalValue()).isEqualByComparingTo("-15028.00");
    assertThat(bma.get("averagePrice").isNull()).isTrue();
    assertThat(bma.get("totalReturnPercent").isNull()).isTrue();
    assertThat(bma.get("dailyChangePercent").decimalValue()).isEqualByComparingTo("-1.15");

    JsonNode metr = position(portfolio, "METR");
    assertThat(metr.get("instrumentId").asLong()).isEqualTo(54);
    assertThat(metr.get("name").asString()).isEqualTo("MetroGAS S.A.");
    assertThat(metr.get("quantity").asLong()).isEqualTo(500);
    assertThat(metr.get("lastPrice").decimalValue()).isEqualByComparingTo("229.50");
    assertThat(metr.get("marketValue").decimalValue()).isEqualByComparingTo("114750.00");
    assertThat(metr.get("averagePrice").decimalValue()).isEqualByComparingTo("250.00");
    assertThat(metr.get("totalReturnPercent").decimalValue()).isEqualByComparingTo("-8.20");
    assertThat(metr.get("dailyChangePercent").decimalValue()).isEqualByComparingTo("-1.08");

    JsonNode pamp = position(portfolio, "PAMP");
    assertThat(pamp.get("instrumentId").asLong()).isEqualTo(47);
    assertThat(pamp.get("name").asString()).isEqualTo("Pampa Holding S.A.");
    assertThat(pamp.get("quantity").asLong()).isEqualTo(40);
    assertThat(pamp.get("lastPrice").decimalValue()).isEqualByComparingTo("925.85");
    assertThat(pamp.get("marketValue").decimalValue()).isEqualByComparingTo("37034.00");
    assertThat(pamp.get("averagePrice").decimalValue()).isEqualByComparingTo("930.00");
    assertThat(pamp.get("totalReturnPercent").decimalValue()).isEqualByComparingTo("-0.45");
    assertThat(pamp.get("dailyChangePercent").decimalValue()).isEqualByComparingTo("0.44");
  }

  @Test
  void writesMoneyWithItsDecimalsAndEmptyValuesAsNullFields() {
    String body = api.get(PORTFOLIO, 1).body();

    assertThat(body).contains("\"totalValue\":889756.00");
    assertThat(body).contains("\"averagePrice\":null");
    assertThat(body).contains("\"totalReturnPercent\":null");
  }

  @Test
  void showsEverythingAsZeroForAUserWithoutOrders() {
    ApiResponse response = api.get(PORTFOLIO, 2);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).contains("\"available\":0.00").contains("\"totalValue\":0.00");
    assertThat(response.json().get("positions")).isEmpty();
  }

  @Test
  void answersNotFoundForAUserThatDoesNotExist() {
    ApiResponse response = api.get(PORTFOLIO, 99);

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.json().get("type").asString()).isEqualTo("/problems/user-not-found");
  }

  @ParameterizedTest
  @ValueSource(strings = {"abc", "99999999999999999999999"})
  void answersBadRequestForAUserIdThatIsNotANumberThatFits(String userId) {
    ApiResponse response = api.get(PORTFOLIO, userId);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo("/problems/validation-error");
  }

  @Test
  void showsTheDailyChangeOfBbarDespiteItsInconsistentPreviousClose() {
    long userId = userWithPosition(BBAR, 10, "1000.00");

    JsonNode bbar = position(portfolioOf(userId), "BBAR");

    assertThat(bbar.get("dailyChangePercent").decimalValue()).isEqualByComparingTo("-0.87");
  }

  @Test
  void usesTheLatestCloseEvenWhenTheDayHadNoTrades() {
    data.addPrice(
        DOME, LocalDate.of(2023, 7, 17), new BigDecimal("21.00"), new BigDecimal("21.00"));
    long userId = userWithPosition(DOME, 10, "20.00");

    JsonNode dome = position(portfolioOf(userId), "DOME");

    assertThat(dome.get("lastPrice").decimalValue()).isEqualByComparingTo("21.00");
    assertThat(dome.get("dailyChangePercent").decimalValue()).isEqualByComparingTo("0.00");
  }

  @Test
  void showsAPositionWithoutPriceWithoutValue() {
    long userId = userWithPosition(PGR, 5, "100.00");

    JsonNode portfolio = portfolioOf(userId);
    JsonNode pgr = position(portfolio, "PGR");

    assertThat(pgr.get("quantity").asLong()).isEqualTo(5);
    assertThat(pgr.get("lastPrice").isNull()).isTrue();
    assertThat(pgr.get("marketValue").isNull()).isTrue();
    assertThat(pgr.get("totalReturnPercent").isNull()).isTrue();
    assertThat(pgr.get("dailyChangePercent").isNull()).isTrue();
    assertThat(portfolio.get("totalValue").decimalValue()).isEqualByComparingTo("99500.00");
  }

  @Test
  void showsTheSharesReservedByAPendingSale() {
    long userId = userWithPosition(PAMP, 100, "900.00");
    data.pending(userId, PAMP, OrderSide.SELL, 30, new BigDecimal("1000.00"));

    JsonNode pamp = position(portfolioOf(userId), "PAMP");

    assertThat(pamp.get("quantity").asLong()).isEqualTo(100);
    assertThat(pamp.get("reservedQuantity").asLong()).isEqualTo(30);
  }
}
