package com.cocos.challenge.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class OrderMetricsTest {

  private static final String RECORDED = "orders.recorded";
  private static final String NO_REASON = "none";
  // 2 sides x 2 types x (FILLED + NEW + REJECTED with each of the 3 reasons)
  private static final int RECORDED_SERIES = 20;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final OrderMetrics metrics = new OrderMetrics(registry);

  private Counter recordedSeries(String side, String type, String status, String reason) {
    return registry
        .get(RECORDED)
        .tag("side", side)
        .tag("type", type)
        .tag("status", status)
        .tag("reason", reason)
        .counter();
  }

  private double totalRecorded() {
    return registry.find(RECORDED).counters().stream().mapToDouble(Counter::count).sum();
  }

  private static StoredOrder order(
      OrderSide side, OrderType type, OrderStatus status, @Nullable RejectionReason reason) {
    return new StoredOrder(
        12,
        1,
        47,
        "PAMP",
        side,
        type,
        10,
        new BigDecimal("925.85"),
        status,
        null,
        null,
        reason,
        OffsetDateTime.of(2026, 11, 8, 11, 2, 15, 0, ZoneOffset.ofHours(-3)));
  }

  @Test
  void everySeriesExistsAtZeroBeforeTheFirstOrder() {
    for (String side : List.of("BUY", "SELL")) {
      for (String type : List.of("MARKET", "LIMIT")) {
        assertThat(recordedSeries(side, type, "FILLED", NO_REASON).count()).isZero();
        assertThat(recordedSeries(side, type, "NEW", NO_REASON).count()).isZero();
        for (String reason :
            List.of("INSUFFICIENT_CASH", "INSUFFICIENT_SHARES", "NO_MARKET_PRICE")) {
          assertThat(recordedSeries(side, type, "REJECTED", reason).count()).isZero();
        }
      }
    }
    assertThat(registry.find(RECORDED).counters()).hasSize(RECORDED_SERIES);
    assertThat(registry.get("orders.replayed").counter().count()).isZero();
    assertThat(registry.get("orders.cancelled").counter().count()).isZero();
  }

  @Test
  void countsAnOrderInItsSeriesWithoutCreatingAnother() {
    metrics.recordCreated(order(OrderSide.SELL, OrderType.LIMIT, OrderStatus.NEW, null));

    assertThat(recordedSeries("SELL", "LIMIT", "NEW", NO_REASON).count()).isEqualTo(1);
    assertThat(totalRecorded()).isEqualTo(1);
    assertThat(registry.find(RECORDED).counters()).hasSize(RECORDED_SERIES);
  }

  @Test
  void countsARejectedOrderUnderItsReason() {
    metrics.recordCreated(
        order(
            OrderSide.BUY,
            OrderType.MARKET,
            OrderStatus.REJECTED,
            RejectionReason.INSUFFICIENT_CASH));

    assertThat(recordedSeries("BUY", "MARKET", "REJECTED", "INSUFFICIENT_CASH").count())
        .isEqualTo(1);
    assertThat(totalRecorded()).isEqualTo(1);
    assertThat(registry.find(RECORDED).counters()).hasSize(RECORDED_SERIES);
  }

  @Test
  void countsRetriesAndCancellationsApart() {
    metrics.recordReplayed();
    metrics.recordCancelled();
    metrics.recordCancelled();

    assertThat(registry.get("orders.replayed").counter().count()).isEqualTo(1);
    assertThat(registry.get("orders.cancelled").counter().count()).isEqualTo(2);
    assertThat(totalRecorded()).isZero();
  }
}
