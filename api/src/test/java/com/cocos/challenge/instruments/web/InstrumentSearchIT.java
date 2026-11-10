package com.cocos.challenge.instruments.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class InstrumentSearchIT {

  private static final String VALIDATION_ERROR = "/problems/validation-error";
  private static final int MAX_QUERY_LENGTH = 50;
  private static final int MAX_RESULTS = 20;
  private static final String INVALID_CHARACTER_TEXT =
      "El texto de búsqueda tiene un carácter que no se puede buscar.";
  private static final String UNREADABLE_PARAMETERS_TEXT =
      "Los parámetros del pedido no se pudieron leer: revisá que cada uno tenga nombre y esté"
          + " escrito en UTF-8.";

  @LocalServerPort private int port;

  private ApiClient api;

  @BeforeEach
  void createClient() {
    api = new ApiClient(port);
  }

  private ApiResponse search(String query) {
    return api.get("/instruments?query={query}", query);
  }

  /** The query goes exactly as written, already percent-encoded. */
  private ApiResponse searchEncoded(String encodedQuery) {
    return api.getEncoded("/instruments?query=" + encodedQuery);
  }

  private List<String> tickers(String query) {
    ApiResponse response = search(query);
    assertThat(response.status()).isEqualTo(200);
    return StreamSupport.stream(response.json().get("instruments").spliterator(), false)
        .map(node -> node.get("ticker").asString())
        .toList();
  }

  private JsonNode first(String query) {
    return search(query).json().get("instruments").get(0);
  }

  @Test
  void findsByWordsOfTheNameInTickerOrder() {
    assertThat(tickers("banco")).containsExactly("BBAR", "BHIP", "BMA", "BPAT");
  }

  @Test
  void ignoresAccentsAndCase() {
    assertThat(tickers("rio")).containsExactly("BHIP", "MOLI", "OEST", "RICH");
    assertThat(tickers("compania")).contains("INTR");
  }

  @Test
  void requiresEveryWordInAnyOrder() {
    assertThat(tickers("grupo galicia")).containsExactly("GGAL");
    assertThat(tickers("gas sur")).containsExactly("CGPA2", "TGSU2");
  }

  @Test
  void ranksExactThenPrefixThenTickerThenNameMatches() {
    assertThat(tickers("pa")).containsExactly("PAMP", "PATA", "BPAT", "CGPA2", "INTR");
    assertThat(tickers("y").getFirst()).isEqualTo("YPFD");
  }

  @Test
  void limitsTheNumberOfResults() {
    assertThat(tickers("a")).hasSize(MAX_RESULTS);
  }

  @Test
  void neverReturnsTheCurrency() {
    assertThat(tickers("pesos")).isEmpty();
    assertThat(tickers("_")).isEmpty();
  }

  @Test
  void showsTheLatestPriceAndTheDailyChange() {
    JsonNode pamp = first("pamp");

    assertThat(pamp.get("lastPrice").decimalValue()).isEqualByComparingTo("925.85");
    assertThat(pamp.get("dailyChangePercent").decimalValue()).isEqualByComparingTo("0.44");
    assertThat(pamp.get("instrumentId").asLong()).isEqualTo(47);
    assertThat(pamp.get("name").asString()).isEqualTo("Pampa Holding S.A.");
  }

  @Test
  void showsNullWhenThereIsNoPrice() {
    JsonNode pgr = first("pgr");

    assertThat(pgr.get("ticker").asString()).isEqualTo("PGR");
    assertThat(pgr.get("lastPrice").isNull()).isTrue();
    assertThat(pgr.get("dailyChangePercent").isNull()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void rejectsABlankQuery(String query) {
    assertRejectedQuery(search(query));
  }

  @Test
  void findsNothingForAQueryMadeOnlyOfAccentMarks() {
    ApiResponse response = searchEncoded("%CC%81");

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("instruments")).isEmpty();
  }

  @Test
  void rejectsAMissingQuery() {
    assertRejectedQuery(api.get("/instruments"));
  }

  @Test
  void rejectsAQueryOverTheMaximumLength() {
    assertRejectedQuery(search("a".repeat(MAX_QUERY_LENGTH + 1)));
  }

  @Test
  void acceptsAQueryOfTheMaximumLengthEvenWithSurroundingSpaces() {
    assertThat(search("a".repeat(MAX_QUERY_LENGTH)).status()).isEqualTo(200);
    assertThat(search("  " + "a".repeat(MAX_QUERY_LENGTH) + "  ").status()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"%00", "a%00b"})
  void rejectsAQueryWithACharacterTheDatabaseCannotSearch(String encodedQuery) {
    ApiResponse response = searchEncoded(encodedQuery);

    assertRejectedQuery(response);
    assertThat(response.json().get("detail").asString()).isEqualTo(INVALID_CHARACTER_TEXT);
  }

  /** Tomcat cannot read a value that is not UTF-8, nor a parameter without a name. */
  @ParameterizedTest(name = "?{0}")
  @ValueSource(strings = {"query=%C3%28", "=x"})
  void rejectsParametersThatCannotBeRead(String encodedParameters) {
    ApiResponse response = api.getEncoded("/instruments?" + encodedParameters);

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
    assertThat(response.json().get("detail").asString()).isEqualTo(UNREADABLE_PARAMETERS_TEXT);
    assertThat(response.json().has("errors")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"o'higgins", "\\", "%"})
  void searchesSqlSpecialCharactersAsPlainText(String query) {
    assertThat(tickers(query)).isEmpty();
  }

  private static void assertRejectedQuery(ApiResponse response) {
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
    assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("query");
  }
}
