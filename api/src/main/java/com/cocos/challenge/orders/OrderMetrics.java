package com.cocos.challenge.orders;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The business counters of the orders (RFC 8.2). */
@Component
public class OrderMetrics {

  // Not "orders.created": OpenMetrics reserves the suffix _created and the client removes it
  private static final String RECORDED = "orders.recorded";
  private static final String REPLAYED = "orders.replayed";
  private static final String CANCELLED = "orders.cancelled";
  private static final String SIDE_TAG = "side";
  private static final String TYPE_TAG = "type";
  private static final String STATUS_TAG = "status";
  private static final String REASON_TAG = "reason";
  private static final String NO_REASON = "none";

  private final MeterRegistry registry;
  private final Counter replayed;
  private final Counter cancelled;

  /**
   * Registers every series at 0: increase() in Prometheus needs two samples of a series, so a
   * counter born with the first order would never show that order in the dashboard.
   */
  public OrderMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.replayed = registry.counter(REPLAYED);
    this.cancelled = registry.counter(CANCELLED);
    Arrays.stream(OrderSide.values())
        .filter(OrderSide::isTrade)
        .forEach(side ->
            Arrays.stream(OrderType.values()).forEach(type -> registerRecordedSeries(side, type)));
  }

  private void registerRecordedSeries(OrderSide side, OrderType type) {
    recordedCounter(side, type, OrderStatus.FILLED, null);
    recordedCounter(side, type, OrderStatus.NEW, null);
    for (RejectionReason reason : RejectionReason.values()) {
      recordedCounter(side, type, OrderStatus.REJECTED, reason);
    }
  }

  public void recordCreated(StoredOrder order) {
    recordedCounter(order.side(), order.type(), order.status(), order.rejectionReason())
        .increment();
  }

  public void recordReplayed() {
    replayed.increment();
  }

  public void recordCancelled() {
    cancelled.increment();
  }

  private Counter recordedCounter(
      OrderSide side, OrderType type, OrderStatus status, @Nullable RejectionReason reason) {
    return registry.counter(
        RECORDED,
        SIDE_TAG,
        side.name(),
        TYPE_TAG,
        type.name(),
        STATUS_TAG,
        status.name(),
        REASON_TAG,
        reason != null ? reason.name() : NO_REASON);
  }
}
