package com.cocos.challenge.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cocos.challenge.account.service.AccountService;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.StoredOrder;
import com.cocos.challenge.orders.error.OrderNotCancellableException;
import com.cocos.challenge.orders.observability.OrderEventLogger;
import com.cocos.challenge.orders.observability.OrderMetrics;
import com.cocos.challenge.orders.repository.OrderRepository;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** A cancellation counts only once its transaction is confirmed. */
class OrderCancellationServiceTest {

  private static final long USER = 1;
  private static final long ORDER_ID = 7;
  private static final StoredOrder CANCELLED = orderWithStatus(OrderStatus.CANCELLED);

  private final AccountService accounts = mock(AccountService.class);
  private final OrderRepository orders = mock(OrderRepository.class);
  private final OrderMetrics metrics = mock(OrderMetrics.class);
  private final OrderEventLogger eventLogger = mock(OrderEventLogger.class);
  private final OrderCancellationService service =
      new OrderCancellationService(accounts, orders, metrics, eventLogger);

  private static StoredOrder orderWithStatus(OrderStatus status) {
    return new StoredOrder(
        ORDER_ID,
        USER,
        31,
        "BMA",
        OrderSide.BUY,
        OrderType.LIMIT,
        60,
        new BigDecimal("1500.00"),
        status,
        null,
        null,
        null,
        OffsetDateTime.of(2023, 7, 13, 11, 13, 20, 0, ZoneOffset.UTC));
  }

  /** What Spring does around a {@code @Transactional} method. */
  @BeforeEach
  void openATransaction() {
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void closeTheTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void countsAndLogsTheCancellationOnlyAfterTheCommit() {
    when(orders.cancelIfNew(USER, ORDER_ID)).thenReturn(Optional.of(CANCELLED));

    assertThat(service.cancel(USER, ORDER_ID)).isSameAs(CANCELLED);
    verifyNoInteractions(metrics, eventLogger);

    TransactionSynchronizationUtils.triggerAfterCommit();

    verify(metrics).recordCancelled();
    verify(eventLogger).logCancelled(CANCELLED);
  }

  @Test
  void aCancellationThatCouldNotHappenRecordsNothing() {
    when(orders.cancelIfNew(USER, ORDER_ID)).thenReturn(Optional.empty());
    when(orders.findById(USER, ORDER_ID))
        .thenReturn(Optional.of(orderWithStatus(OrderStatus.FILLED)));

    assertThatThrownBy(() -> service.cancel(USER, ORDER_ID))
        .isInstanceOf(OrderNotCancellableException.class);
    TransactionSynchronizationUtils.triggerAfterCommit();

    verifyNoInteractions(metrics, eventLogger);
  }
}
