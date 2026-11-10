package com.cocos.challenge.support;

import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Creates the users, orders and prices the tests need, on top of the original data. */
@Component
public class TestData {

  /** Every test email ends like this, so a test can look for emails in the logs. */
  public static final String EMAIL_DOMAIN = "@test.com";

  private static final long ARS = 66;
  private static final BigDecimal CASH_PRICE = BigDecimal.ONE;

  private static final String INSERT_USER =
      "insert into users (email, accountNumber) values (:email, :accountNumber) returning id";
  private static final String INSERT_ORDER =
      """
      insert into orders (instrumentId, userId, size, price, side, status, type, datetime)
      values (:instrumentId, :userId, :size, :price, :side, :status, :type,
              coalesce(cast(:datetime as timestamptz), clock_timestamp()))
      """;
  private static final String INSERT_PRICE =
      """
      insert into marketdata (instrumentId, date, close, previousClose)
      values (:instrumentId, :date, :close, :previousClose)
      """;

  private final AtomicLong sequence = new AtomicLong();
  private final JdbcClient jdbc;

  public TestData(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long createUser() {
    long number = sequence.incrementAndGet();
    return jdbc.sql(INSERT_USER)
        .param("email", "user-" + number + EMAIL_DOMAIN)
        .param("accountNumber", "TEST-" + number)
        .query(Long.class)
        .single();
  }

  /** A deposit is {@code amount} pesos at price 1. */
  public void deposit(long userId, int amount) {
    insertOrder(
        userId,
        ARS,
        OrderSide.CASH_IN,
        OrderStatus.FILLED,
        OrderType.MARKET,
        amount,
        CASH_PRICE,
        null);
  }

  public void fill(long userId, long instrumentId, OrderSide side, int size, BigDecimal price) {
    insertOrder(
        userId, instrumentId, side, OrderStatus.FILLED, OrderType.MARKET, size, price, null);
  }

  public void pending(long userId, long instrumentId, OrderSide side, int size, BigDecimal price) {
    insertOrder(userId, instrumentId, side, OrderStatus.NEW, OrderType.LIMIT, size, price, null);
  }

  /** A filled order with the given time, instead of the time of the insert. */
  public void fillAt(
      long userId,
      long instrumentId,
      OrderSide side,
      int size,
      BigDecimal price,
      OffsetDateTime datetime) {
    insertOrder(
        userId, instrumentId, side, OrderStatus.FILLED, OrderType.MARKET, size, price, datetime);
  }

  /** A pending LIMIT order with the given time, instead of the time of the insert. */
  public void pendingAt(
      long userId, long instrumentId, int size, BigDecimal price, OffsetDateTime datetime) {
    insertOrder(
        userId,
        instrumentId,
        OrderSide.BUY,
        OrderStatus.NEW,
        OrderType.LIMIT,
        size,
        price,
        datetime);
  }

  /** Records a day without trades: open, high and low stay empty. */
  public void addPrice(
      long instrumentId, LocalDate date, BigDecimal close, @Nullable BigDecimal previousClose) {
    jdbc.sql(INSERT_PRICE)
        .param("instrumentId", instrumentId)
        .param("date", date)
        .param("close", close)
        .param("previousClose", previousClose)
        .update();
  }

  private void insertOrder(
      long userId,
      long instrumentId,
      OrderSide side,
      OrderStatus status,
      OrderType type,
      int size,
      BigDecimal price,
      @Nullable OffsetDateTime datetime) {
    jdbc.sql(INSERT_ORDER)
        .param("instrumentId", instrumentId)
        .param("userId", userId)
        .param("size", size)
        .param("price", price)
        .param("side", side.name())
        .param("status", status.name())
        .param("type", type.name())
        .param("datetime", datetime)
        .update();
  }
}
