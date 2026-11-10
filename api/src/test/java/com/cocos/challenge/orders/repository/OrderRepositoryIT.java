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

@IntegrationTest
class OrderRepositoryIT {

  private static final long USER_ONE = 1;
  private static final long USER_TWO = 2;
  private static final long PAMP = 47;
  private static final long FIRST_NEW_ORDER_ID = 12;
  private static final String FINGERPRINT = "a".repeat(64);

  @Autowired private OrderRepository repository;

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
