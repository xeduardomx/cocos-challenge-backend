package com.cocos.challenge.orders.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cocos.challenge.orders.domain.NewOrder;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.orders.domain.StoredOrder;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import com.cocos.challenge.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class OrderRepositoryIT {

  private static final long USER_ONE = 1;
  private static final long USER_TWO = 2;
  private static final long PAMP = 47;
  private static final long FIRST_NEW_ORDER_ID = 12;
  private static final String FINGERPRINT = "a".repeat(64);
  private static final Duration WINDOW = Duration.ofSeconds(5);

  // What this connection did over orders and has not reported yet: it can include statements from
  // before the transaction, so the test looks at how much each number grows.
  private static final String INDEX_USE_OVER_ORDERS =
      """
      select coalesce(sum(idx_scan), 0) as scans, coalesce(sum(idx_tup_fetch), 0) as rows_fetched
      from pg_stat_xact_user_tables where relid = 'public.orders'::regclass
      """;

  @Autowired private OrderRepository repository;
  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transaction;

  private static NewOrder buy(long userId, UUID clientOrderId) {
    return new NewOrder(
        userId,
        PAMP,
        OrderSide.BUY,
        OrderType.MARKET,
        10,
        new BigDecimal("925.85"),
        OrderStatus.FILLED,
        clientOrderId,
        FINGERPRINT,
        null);
  }

  @Test
  void insertsAnOrderAndReturnsItWithItsTickerAndTheTimeOfTheDatabase() {
    UUID clientOrderId = UUID.randomUUID();
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofMinutes(1));

    StoredOrder stored = repository.insert(buy(USER_ONE, clientOrderId));

    assertThat(stored.id()).isEqualTo(FIRST_NEW_ORDER_ID);
    assertThat(stored.ticker()).isEqualTo("PAMP");
    assertThat(stored.userId()).isEqualTo(USER_ONE);
    assertThat(stored.side()).isEqualTo(OrderSide.BUY);
    assertThat(stored.type()).isEqualTo(OrderType.MARKET);
    assertThat(stored.size()).isEqualTo(10);
    assertThat(stored.price()).isEqualByComparingTo("925.85");
    assertThat(stored.status()).isEqualTo(OrderStatus.FILLED);
    assertThat(stored.clientOrderId()).isEqualTo(clientOrderId);
    assertThat(stored.requestFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(stored.rejectionReason()).isNull();
    assertThat(stored.createdAt()).isAfter(before).isBefore(before.plus(Duration.ofMinutes(2)));
  }

  @Test
  void storesTheRejectionReasonAndAMissingPrice() {
    NewOrder rejected =
        new NewOrder(
            USER_ONE,
            3,
            OrderSide.BUY,
            OrderType.MARKET,
            10,
            null,
            OrderStatus.REJECTED,
            UUID.randomUUID(),
            FINGERPRINT,
            RejectionReason.NO_MARKET_PRICE);

    StoredOrder stored = repository.insert(rejected);

    assertThat(stored.price()).isNull();
    assertThat(stored.rejectionReason()).isEqualTo(RejectionReason.NO_MARKET_PRICE);
  }

  @Test
  void findsTheNewestOrderOfAnEqualRequestInsideTheWindow() {
    repository.insert(buy(USER_ONE, UUID.randomUUID()));
    StoredOrder newest = repository.insert(buy(USER_ONE, UUID.randomUUID()));

    assertThat(repository.findRecentEqualOrderId(USER_ONE, UUID.randomUUID(), FINGERPRINT, WINDOW))
        .contains(newest.id());
  }

  @Test
  void findsNoEqualOrderWithAWindowOfZero() {
    repository.insert(buy(USER_ONE, UUID.randomUUID()));

    assertThat(
            repository.findRecentEqualOrderId(
                USER_ONE, UUID.randomUUID(), FINGERPRINT, Duration.ZERO))
        .isEmpty();
  }

  /**
   * The window has to bound the index scan, not filter every order of the user afterwards: with a
   * volatile clock, such as clock_timestamp(), Postgres reads them all. Here that would be the 11
   * original orders of user 1, all from 2023. The test table is tiny, so the plans that would hide
   * the difference are turned off for this transaction.
   */
  @Test
  void readsNoOrderOfTheUserFromOutsideTheWindow() {
    IndexUse used =
        transaction.execute(status -> {
          jdbc.sql("set local enable_seqscan = off").update();
          jdbc.sql("set local enable_bitmapscan = off").update();
          IndexUse before = indexUseOverOrders();
          repository.findRecentEqualOrderId(USER_ONE, UUID.randomUUID(), FINGERPRINT, WINDOW);
          return indexUseOverOrders().since(before);
        });

    assertThat(used).isEqualTo(new IndexUse(1, 0));
  }

  private IndexUse indexUseOverOrders() {
    return jdbc.sql(INDEX_USE_OVER_ORDERS).query(IndexUse.class).single();
  }

  /** How many index scans ran over orders, and how many rows they fetched from the table. */
  private record IndexUse(long scans, long rowsFetched) {

    IndexUse since(IndexUse earlier) {
      return new IndexUse(scans - earlier.scans, rowsFetched - earlier.rowsFetched);
    }
  }

  @Test
  void leavesOutTheOrderOfTheSameClientOrderId() {
    UUID clientOrderId = UUID.randomUUID();
    repository.insert(buy(USER_ONE, clientOrderId));

    assertThat(repository.findRecentEqualOrderId(USER_ONE, clientOrderId, FINGERPRINT, WINDOW))
        .isEmpty();
  }

  @Test
  void findsAnOrderByItsClientOrderId() {
    UUID clientOrderId = UUID.randomUUID();
    StoredOrder stored = repository.insert(buy(USER_ONE, clientOrderId));

    assertThat(repository.findByClientOrderId(USER_ONE, clientOrderId)).contains(stored);
  }

  @Test
  void doesNotFindTheClientOrderIdOfAnotherUser() {
    UUID clientOrderId = UUID.randomUUID();
    repository.insert(buy(USER_ONE, clientOrderId));

    assertThat(repository.findByClientOrderId(USER_TWO, clientOrderId)).isEmpty();
  }

  @Test
  void refusesARepeatedClientOrderIdOfTheSameUser() {
    UUID clientOrderId = UUID.randomUUID();
    repository.insert(buy(USER_ONE, clientOrderId));

    assertThatThrownBy(() -> repository.insert(buy(USER_ONE, clientOrderId)))
        .isInstanceOf(DuplicateKeyException.class);
  }
}
