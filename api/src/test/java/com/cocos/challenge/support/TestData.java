package com.cocos.challenge.support;

import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
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
      values (:instrumentId, :userId, :size, :price, :side, :status, :type, clock_timestamp())
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
        userId, ARS, OrderSide.CASH_IN, OrderStatus.FILLED, OrderType.MARKET, amount, CASH_PRICE);
  }

  public void fill(long userId, long instrumentId, OrderSide side, int size, BigDecimal price) {
    insertOrder(userId, instrumentId, side, OrderStatus.FILLED, OrderType.MARKET, size, price);
  }

  public void pending(long userId, long instrumentId, OrderSide side, int size, BigDecimal price) {
    insertOrder(userId, instrumentId, side, OrderStatus.NEW, OrderType.LIMIT, size, price);
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
      BigDecimal price) {
    jdbc.sql(INSERT_ORDER)
        .param("instrumentId", instrumentId)
        .param("userId", userId)
        .param("size", size)
        .param("price", price)
        .param("side", side.name())
        .param("status", status.name())
        .param("type", type.name())
        .update();
  }
}
