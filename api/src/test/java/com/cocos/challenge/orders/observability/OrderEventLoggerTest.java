package com.cocos.challenge.orders.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.StoredOrder;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import com.cocos.challenge.shared.web.RequestIds;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** What each line has in it is checked on the real output, in {@code LoggingIT}. */
class OrderEventLoggerTest {

  private static final StoredOrder ORDER =
      new StoredOrder(
          12,
          1,
          47,
          "PAMP",
          OrderSide.BUY,
          OrderType.MARKET,
          10,
          new BigDecimal("925.85"),
          OrderStatus.FILLED,
          UUID.randomUUID(),
          "fingerprint",
          null,
          OffsetDateTime.of(2026, 11, 8, 11, 2, 15, 0, ZoneOffset.ofHours(-3)));

  private final OrderEventLogger logger = new OrderEventLogger();

  @AfterEach
  void clearTheLogContext() {
    MDC.clear();
  }

  @Test
  void leavesTheLogContextAsItFoundIt() {
    MDC.put(RequestIds.MDC_KEY, "a-request");

    logger.logProcessed(ORDER, false);
    logger.logProcessed(ORDER, true);
    logger.logCancelled(ORDER);

    assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys(RequestIds.MDC_KEY);
  }
}
