package com.cocos.challenge.orders.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cocos.challenge.orders.OrderEventLogger;
import com.cocos.challenge.orders.OrderMetrics;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.orders.StoredOrder;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class OrderSubmissionServiceTest {

  private static final SubmitOrderCommand COMMAND =
      new SubmitOrderCommand(
          1, UUID.randomUUID(), 47, OrderSide.BUY, OrderType.MARKET, 10, null, null);
  private static final SubmissionResult RESULT =
      new SubmissionResult(
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
              COMMAND.clientOrderId(),
              "fingerprint",
              null,
              OffsetDateTime.of(2026, 11, 8, 11, 2, 15, 0, ZoneOffset.ofHours(-3))),
          true);

  private final OrderSubmissionTransaction transaction = mock(OrderSubmissionTransaction.class);
  private final OrderMetrics metrics = mock(OrderMetrics.class);
  private final OrderEventLogger eventLogger = mock(OrderEventLogger.class);
  private final OrderSubmissionService service =
      new OrderSubmissionService(transaction, metrics, eventLogger);

  @Test
  void returnsTheResultOfTheTransaction() {
    when(transaction.submit(COMMAND)).thenReturn(RESULT);

    assertThat(service.submit(COMMAND)).isSameAs(RESULT);
    verify(transaction, times(1)).submit(COMMAND);
  }

  @Test
  void triesAgainOnceWhenTheClientOrderIdWasTakenInTheMeantime() {
    when(transaction.submit(COMMAND))
        .thenThrow(new DuplicateKeyException("orders_user_clientorderid_key"))
        .thenReturn(RESULT);

    assertThat(service.submit(COMMAND)).isSameAs(RESULT);
    verify(transaction, times(2)).submit(COMMAND);
  }

  @Test
  void letsASecondDuplicateKeyErrorThrough() {
    DuplicateKeyException duplicate = new DuplicateKeyException("orders_user_clientorderid_key");
    when(transaction.submit(COMMAND)).thenThrow(duplicate);

    assertThatThrownBy(() -> service.submit(COMMAND)).isSameAs(duplicate);
    verify(transaction, times(2)).submit(COMMAND);
  }

  @Test
  void countsAndLogsANewOrder() {
    SubmissionResult created = new SubmissionResult(RESULT.order(), false);
    when(transaction.submit(COMMAND)).thenReturn(created);

    service.submit(COMMAND);

    verify(metrics).recordCreated(created.order());
    verify(metrics, never()).recordReplayed();
    verify(eventLogger).logProcessed(created.order(), false);
  }

  @Test
  void countsAndLogsARetryAsAReplay() {
    when(transaction.submit(COMMAND)).thenReturn(RESULT);

    service.submit(COMMAND);

    verify(metrics).recordReplayed();
    verify(metrics, never()).recordCreated(any());
    verify(eventLogger).logProcessed(RESULT.order(), true);
  }

  @Test
  void countsOnceAnOrderThatNeededASecondTry() {
    when(transaction.submit(COMMAND))
        .thenThrow(new DuplicateKeyException("orders_user_clientorderid_key"))
        .thenReturn(RESULT);

    service.submit(COMMAND);

    verify(metrics, times(1)).recordReplayed();
    verify(eventLogger, times(1)).logProcessed(RESULT.order(), true);
  }

  @Test
  void countsAndLogsNothingWhenTheOrderWasNotConfirmed() {
    when(transaction.submit(COMMAND)).thenThrow(new IllegalStateException("not confirmed"));

    assertThatThrownBy(() -> service.submit(COMMAND)).isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(metrics, eventLogger);
  }
}
