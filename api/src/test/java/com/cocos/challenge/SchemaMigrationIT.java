package com.cocos.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cocos.challenge.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class SchemaMigrationIT {

  private static final String BASELINE_VERSION = "1";
  private static final String BASELINE_TYPE = "BASELINE";
  private static final String SQL_TYPE = "SQL";
  private static final List<String> RFC_VERSIONS = List.of("2", "3", "4", "5", "6");
  private static final int OLD_ORDER_ID = 7;
  private static final int ORDER_WITH_TIME = 2;
  private static final ZoneOffset BUENOS_AIRES = ZoneOffset.ofHours(-3);
  private static final String PAUSE_SECONDS = "0.2";
  private static final String FINGERPRINT = "md5('x') || md5('x')";
  private static final String UUID_VALUE = "gen_random_uuid()";

  private static final Map<String, String> VALID_ORDER =
      Map.of(
          "instrumentid", "47",
          "userid", "1",
          "size", "10",
          "price", "925.85",
          "type", "'MARKET'",
          "side", "'BUY'",
          "status", "'FILLED'");

  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transaction;

  private record Migration(String version, String type, boolean success) {}

  static Stream<Arguments> impossibleOrders() {
    return Stream.of(
        Arguments.of("without userid", Map.of("userid", "null")),
        Arguments.of("without size", Map.of("size", "null")),
        Arguments.of("with an explicit null datetime", Map.of("datetime", "null")),
        Arguments.of("with side COMPRA", Map.of("side", "'COMPRA'")),
        Arguments.of("with type STOP", Map.of("type", "'STOP'")),
        Arguments.of("with status DONE", Map.of("status", "'DONE'")),
        Arguments.of("with size 0", Map.of("size", "0")),
        Arguments.of("with price 0", Map.of("price", "0")),
        Arguments.of("filled without price", Map.of("price", "null")),
        Arguments.of("cash in with price 2", Map.of("side", "'CASH_IN'", "price", "2")),
        Arguments.of(
            "filled with a rejection reason", Map.of("rejectionreason", "'NO_MARKET_PRICE'")),
        Arguments.of(
            "rejected with the reason OTHER",
            Map.of("status", "'REJECTED'", "rejectionreason", "'OTHER'")),
        Arguments.of("identifier without fingerprint", Map.of("clientorderid", UUID_VALUE)));
  }

  @Test
  void appliesTheRfcMigrationsOverTheCocosBaseline() {
    List<Migration> history =
        jdbc.sql("select version, type, success from flyway_schema_history order by installed_rank")
            .query((rs, row) ->
                new Migration(
                    rs.getString("version"), rs.getString("type"), rs.getBoolean("success")))
            .list();

    assertThat(history.get(0)).isEqualTo(new Migration(BASELINE_VERSION, BASELINE_TYPE, true));
    List<Migration> migrations = history.subList(1, history.size());
    assertThat(migrations).extracting(Migration::version).containsExactlyElementsOf(RFC_VERSIONS);
    assertThat(migrations).allMatch(m -> m.type().equals(SQL_TYPE) && m.success());
  }

  @Test
  void keepsTheOriginalData() {
    assertThat(count("users")).isEqualTo(4);
    assertThat(count("instruments")).isEqualTo(66);
    assertThat(count("marketdata")).isEqualTo(126);
    assertThat(count("orders")).isEqualTo(11);
    OffsetDateTime time =
        jdbc.sql("select datetime from orders where id = ?")
            .param(ORDER_WITH_TIME)
            .query(OffsetDateTime.class)
            .single();
    assertThat(time.toInstant())
        .isEqualTo(OffsetDateTime.of(2023, 7, 12, 12, 31, 20, 0, BUENOS_AIRES).toInstant());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("impossibleOrders")
  void rejectsImpossibleOrders(String description, Map<String, String> overrides) {
    assertThatThrownBy(() -> insertOrder(overrides))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsARepeatedIdentifierForTheSameUser() {
    String identifier = "'" + UUID.randomUUID() + "'";
    Map<String, String> order =
        Map.of("clientorderid", identifier, "requestfingerprint", FINGERPRINT);
    insertOrder(order);

    assertThatThrownBy(() -> insertOrder(order)).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void acceptsTheSameIdentifierForAnotherUser() {
    String identifier = "'" + UUID.randomUUID() + "'";
    insertOrder(
        Map.of("clientorderid", identifier, "requestfingerprint", FINGERPRINT, "userid", "1"));

    assertThatCode(() ->
            insertOrder(
                Map.of(
                    "clientorderid", identifier, "requestfingerprint", FINGERPRINT, "userid", "2")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsTwoPricesForTheSameInstrumentAndDate() {
    String existingDate =
        jdbc.sql("select date::text from marketdata where instrumentid = 47 limit 1")
            .query(String.class)
            .single();

    assertThatThrownBy(() -> insertPrice("47, '" + existingDate + "', 10, 12, 9, 11, 10"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void rejectsACloseOutsideTheDayRange() {
    assertThatThrownBy(() -> insertPrice("47, '2030-01-01', 10, 12, 9, 13, 10"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsAZeroClose() {
    assertThatThrownBy(() -> insertPrice("47, '2030-01-01', 10, 12, 0, 0, 10"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsAnInstrumentWithoutTicker() {
    assertThatThrownBy(() -> jdbc.sql(insertInstrumentSql("null")).update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void rejectsARepeatedTicker() {
    String existingTicker =
        jdbc.sql("select ticker from instruments limit 1").query(String.class).single();

    assertThatThrownBy(() -> jdbc.sql(insertInstrumentSql("'" + existingTicker + "'")).update())
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void stampsTheOrderTimeWhenItIsWritten() {
    record Times(OffsetDateTime transactionStart, OffsetDateTime orderTime) {}

    Times times =
        transaction.execute(status -> {
          OffsetDateTime start = jdbc.sql("select now()").query(OffsetDateTime.class).single();
          jdbc.sql("select pg_sleep(" + PAUSE_SECONDS + ")").query().listOfRows();
          OffsetDateTime stamped =
              jdbc.sql(insertOrderSql(VALID_ORDER) + " returning datetime")
                  .query(OffsetDateTime.class)
                  .single();
          return new Times(start, stamped);
        });

    assertThat(times).isNotNull();
    assertThat(times.orderTime()).isAfter(times.transactionStart());
  }

  @Test
  void cancelsAnOldOrderWithoutIdentifier() {
    int updated =
        jdbc.sql("update orders set status = 'CANCELLED' where id = ?")
            .param(OLD_ORDER_ID)
            .update();

    assertThat(updated).isEqualTo(1);
  }

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }

  private void insertOrder(Map<String, String> overrides) {
    jdbc.sql(insertOrderSql(overrides)).update();
  }

  private static String insertOrderSql(Map<String, String> overrides) {
    Map<String, String> columns = new LinkedHashMap<>(VALID_ORDER);
    columns.putAll(overrides);
    String names = String.join(", ", columns.keySet());
    String values = columns.values().stream().collect(Collectors.joining(", "));
    return "insert into orders (" + names + ") values (" + values + ")";
  }

  private void insertPrice(String values) {
    jdbc.sql(
            "insert into marketdata (instrumentid, date, open, high, low, close, previousclose)"
                + " values ("
                + values
                + ")")
        .update();
  }

  private static String insertInstrumentSql(String ticker) {
    return "insert into instruments (ticker, name, type) values ("
        + ticker
        + ", 'Test', 'ACCIONES')";
  }
}
