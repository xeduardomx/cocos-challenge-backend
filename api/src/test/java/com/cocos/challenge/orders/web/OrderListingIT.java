package com.cocos.challenge.orders.web;

import static java.util.Comparator.reverseOrder;
import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import com.cocos.challenge.support.TestData;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** Listing the orders of a user, newest first, with a cursor (RFC 4.4 and 6.2). */
@IntegrationTest
class OrderListingIT {

  private static final String LIST = "/users/{userId}/orders";
  private static final String LIST_BY_STATUS = LIST + "?status={status}";
  private static final String LIST_WITH_LIMIT = LIST + "?limit={limit}";
  private static final String LIST_PAGE = LIST + "?limit={limit}&cursor={cursor}";
  private static final String CANCEL = "/users/{userId}/orders/{orderId}/cancel";
  private static final String VALIDATION_ERROR = "/problems/validation-error";
  private static final String USER_NOT_FOUND = "/problems/user-not-found";
  private static final long USER_ONE = 1;
  private static final long USER_TWO = 2;
  private static final long MISSING_USER = 99;
  private static final long PAMP = 47;
  private static final BigDecimal PRICE = new BigDecimal("10.00");
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int PENDING_ORDERS = 30;
  private static final int CANCELLED_FROM_THE_PAGE = 5;
  private static final OffsetDateTime SOME_TIME =
      OffsetDateTime.parse("2026-01-15T10:00:00.000100Z");
  private static final String BAD_LIMIT_TEXT = "El tamaño de página va de 1 a 100.";
  private static final String BAD_CURSOR_TEXT =
      "La marca de página no es válida: usá el nextCursor de la respuesta anterior.";

  @LocalServerPort private int port;
  @Autowired private TestData data;

  private ApiClient api;

  @BeforeEach
  void prepare() {
    api = new ApiClient(port);
  }

  @Test
  void filtersByStatus() {
    ApiResponse response = api.get(LIST_BY_STATUS, USER_ONE, "NEW");

    assertThat(response.status()).isEqualTo(200);
    assertThat(ids(response)).containsExactly(7L, 5L);
    assertThat(response.json().get("nextCursor").isNull()).isTrue();
  }

  @Test
  void sortsTheOrdersWithTheSameTimeById() {
    long user = data.createUser();
    data.pendingAt(user, PAMP, 1, PRICE, SOME_TIME);
    data.pendingAt(user, PAMP, 2, PRICE, SOME_TIME);

    ApiResponse all = api.get(LIST, user);
    List<Long> walked = walk(user, 1);

    assertThat(ids(all)).hasSize(2).isSortedAccordingTo(reverseOrder());
    assertThat(walked).containsExactlyElementsOf(ids(all));
  }

  @Test
  void listsEveryOrderOfAUserNewestFirst() {
    ApiResponse response = api.get(LIST, USER_ONE);

    assertThat(response.status()).isEqualTo(200);
    assertThat(ids(response)).containsExactly(11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
    assertThat(response.json().get("nextCursor").isNull()).isTrue();
  }

  @Test
  void showsADepositAndAWithdrawalAsAnAmount() {
    JsonNode orders = api.get(LIST, USER_ONE).json().get("orders");
    JsonNode withdrawal = order(orders, 8);
    JsonNode deposit = order(orders, 1);

    assertThat(deposit.get("quantity").isNull()).isTrue();
    assertThat(deposit.get("price").isNull()).isTrue();
    assertThat(deposit.get("amount").decimalValue()).isEqualByComparingTo("1000000.00");
    assertThat(withdrawal.get("amount").decimalValue()).isEqualByComparingTo("100000.00");
  }

  @Test
  void showsTheOldOrdersWithoutClientIdOrRejectionReason() {
    JsonNode old = order(api.get(LIST, USER_ONE).json().get("orders"), 1);

    assertThat(old.get("clientOrderId").isNull()).isTrue();
    assertThat(old.get("rejectionReason").isNull()).isTrue();
  }

  @Test
  void showsTheDateInTheDisplayZone() {
    JsonNode second = order(api.get(LIST, USER_ONE).json().get("orders"), 2);

    assertThat(second.get("datetime").asString()).isEqualTo("2023-07-12T12:31:20-03:00");
  }

  @Test
  void doesNotSkipAnyOrderWhenOrdersAreCancelledBetweenPages() {
    long user = data.createUser();
    for (int i = 0; i < PENDING_ORDERS; i++) {
      data.pending(user, PAMP, OrderSide.BUY, 1, PRICE);
    }

    ApiResponse first = api.get(LIST_BY_STATUS, user, "NEW");
    List<Long> firstIds = ids(first);
    String cursor = first.json().get("nextCursor").asString();
    for (Long id : firstIds.subList(0, CANCELLED_FROM_THE_PAGE)) {
      String path =
          CANCEL.replace("{userId}", String.valueOf(user)).replace("{orderId}", String.valueOf(id));
      assertThat(api.post(path, "").status()).isEqualTo(200);
    }
    ApiResponse second = api.get(LIST_BY_STATUS + "&cursor={cursor}", user, "NEW", cursor);

    assertThat(firstIds).hasSize(DEFAULT_PAGE_SIZE);
    assertThat(ids(second)).hasSize(PENDING_ORDERS - DEFAULT_PAGE_SIZE);
    assertThat(ids(second)).allMatch(id -> id < firstIds.get(firstIds.size() - 1));
    assertThat(second.json().get("nextCursor").isNull()).isTrue();
  }

  @Test
  void walksOrdersThatAreMicrosecondsApart() {
    long user = data.createUser();
    for (int i = 0; i < 5; i++) {
      data.pendingAt(user, PAMP, 1, PRICE, SOME_TIME.plusNanos(i * 1000L));
    }

    List<Long> walked = walk(user, 2);

    assertThat(walked).hasSize(5).doesNotHaveDuplicates();
    assertThat(walked).isSortedAccordingTo(reverseOrder());
  }

  @Test
  void answersAnEmptyListForAUserWithoutOrders() {
    ApiResponse response = api.get(LIST, USER_TWO);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.json().get("orders")).isEmpty();
    assertThat(response.json().get("nextCursor").isNull()).isTrue();
  }

  @Test
  void answersNotFoundForAMissingUser() {
    ApiResponse response = api.get(LIST, MISSING_USER);

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.json().get("type").asString()).isEqualTo(USER_NOT_FOUND);
  }

  @Test
  void rejectsACursorThatCannotBeRead() {
    ApiResponse response = api.get(LIST_PAGE, USER_ONE, 5, "garbage");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
    assertThat(field(response)).isEqualTo("cursor");
    assertThat(response.json().get("detail").asString()).isEqualTo(BAD_CURSOR_TEXT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void takesABlankCursorAsTheFirstPage(String cursor) {
    ApiResponse response = api.get(LIST_PAGE, USER_ONE, 5, cursor);

    assertThat(response.status()).isEqualTo(200);
    assertThat(ids(response)).containsExactly(11L, 10L, 9L, 8L, 7L);
    assertThat(response.json().get("nextCursor").isNull()).isFalse();
  }

  @Test
  void rejectsACursorWithADateThatTheDatabaseCannotHold() {
    String content = "+999999999-12-31T23:59:59Z|1";
    String cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(content.getBytes(StandardCharsets.UTF_8));

    ApiResponse response = api.get(LIST_PAGE, USER_ONE, 5, cursor);

    assertThat(response.status()).isEqualTo(400);
    assertThat(field(response)).isEqualTo("cursor");
  }

  @Test
  void rejectsAPageSizeOutOfRange() {
    for (int limit : new int[] {101, 0}) {
      ApiResponse response = api.get(LIST_WITH_LIMIT, USER_ONE, limit);

      assertThat(response.status()).isEqualTo(400);
      assertThat(field(response)).isEqualTo("limit");
      assertThat(response.json().get("detail").asString()).isEqualTo(BAD_LIMIT_TEXT);
    }
  }

  @Test
  void rejectsAStatusThatDoesNotExist() {
    ApiResponse response = api.get(LIST_BY_STATUS, USER_ONE, "FOO");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
    assertThat(field(response)).isEqualTo("status");
    assertThat(response.json().get("detail").asString())
        .isEqualTo(
            "El valor de status no es válido: tiene que ser uno de NEW, FILLED, REJECTED,"
                + " CANCELLED.");
  }

  @Test
  void rejectsAPageSizeThatIsNotANumber() {
    ApiResponse response = api.get(LIST_WITH_LIMIT, USER_ONE, "x");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.json().get("type").asString()).isEqualTo(VALIDATION_ERROR);
  }

  /** Follows nextCursor until the end and returns the ids in the order they came. */
  private List<Long> walk(long userId, int limit) {
    List<Long> walked = new ArrayList<>();
    String cursor = "";
    do {
      ApiResponse page =
          cursor.isEmpty()
              ? api.get(LIST_WITH_LIMIT, userId, limit)
              : api.get(LIST_PAGE, userId, limit, cursor);
      walked.addAll(ids(page));
      JsonNode next = page.json().get("nextCursor");
      cursor = next.isNull() ? "" : next.asString();
    } while (!cursor.isEmpty());
    return walked;
  }

  private static List<Long> ids(ApiResponse response) {
    List<Long> ids = new ArrayList<>();
    response.json().get("orders").forEach(order -> ids.add(order.get("id").asLong()));
    return ids;
  }

  private static JsonNode order(JsonNode orders, long id) {
    for (JsonNode order : orders) {
      if (order.get("id").asLong() == id) {
        return order;
      }
    }
    throw new AssertionError("order " + id + " is not in the list");
  }

  private static String field(ApiResponse response) {
    return response.json().get("errors").get(0).get("field").asString();
  }
}
