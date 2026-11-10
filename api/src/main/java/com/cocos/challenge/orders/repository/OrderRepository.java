package com.cocos.challenge.orders.repository;

import com.cocos.challenge.orders.domain.NewOrder;
import com.cocos.challenge.orders.domain.OrderType;
import com.cocos.challenge.orders.domain.RejectionReason;
import com.cocos.challenge.orders.domain.StoredOrder;
import com.cocos.challenge.orders.domain.listing.OrderSortKey;
import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

  private static final String SELECT =
      """
      select o.id, o.userid, o.instrumentid, i.ticker, o.side, o.type, o.size, o.price, o.status,
             o.clientorderid, o.requestfingerprint, o.rejectionreason, o.datetime
      """;
  private static final String FIND_BY_CLIENT_ORDER_ID =
      SELECT
          + """
          from orders o join instruments i on i.id = o.instrumentid
          where o.userid = :userId and o.clientorderid = :clientOrderId
          """;
  private static final String FIND_BY_ID =
      SELECT
          + """
          from orders o join instruments i on i.id = o.instrumentid
          where o.userid = :userId and o.id = :orderId
          """;
  // The index by (userid, datetime, id) reads only the orders of the user inside the window. The
  // order with the same client order id is left out: that one is a retry, and the lookup by
  // identifier or the uniqueness rule answers it (RFC 5.3).
  private static final String FIND_RECENT_EQUAL_ORDER_ID =
      """
      select id from orders
      where userid = :userId
        and datetime > clock_timestamp() - :windowMillis * interval '1 millisecond'
        and requestfingerprint = :requestFingerprint
        and clientorderid <> :clientOrderId
        and status in (:acceptedStatuses)
      order by datetime desc, id desc
      limit 1
      """;
  private static final List<String> ACCEPTED_STATUSES =
      List.of(OrderStatus.FILLED.name(), OrderStatus.NEW.name());
  // The page query is built from these fixed clauses; the values always go as named parameters.
  // No "(:cursor is null or ...)": Postgres could fall to a generic plan that does not use the
  // cursor on the index.
  private static final String PAGE_FROM =
      """
      from orders o join instruments i on i.id = o.instrumentid
      where o.userid = :userId
      """;
  private static final String PAGE_STATUS_CLAUSE = "and o.status = :status\n";
  private static final String PAGE_AFTER_CLAUSE =
      "and (o.datetime, o.id) < (:afterDatetime, :afterId)\n";
  private static final String PAGE_ORDER = "order by o.datetime desc, o.id desc limit :limit";
  // the update locks the row of the order: a second cancellation waits and then finds it no longer
  // NEW
  private static final String CANCEL_IF_NEW =
      """
      with o as (
        update orders set status = :cancelledStatus
        where id = :orderId and userid = :userId and status = :newStatus
        returning *)
      """
          + SELECT
          + "from o join instruments i on i.id = o.instrumentid";
  // the insert and the ticker lookup are one statement: no second query for the ticker
  private static final String INSERT =
      """
      with o as (
        insert into orders (userid, instrumentid, side, type, size, price, status,
                            clientorderid, requestfingerprint, rejectionreason)
        values (:userId, :instrumentId, :side, :type, :size, :price, :status,
                :clientOrderId, :requestFingerprint, :rejectionReason)
        returning *)
      """
          + SELECT
          + "from o join instruments i on i.id = o.instrumentid";

  private static final RowMapper<StoredOrder> ORDER_MAPPER = OrderRepository::toOrder;

  private final JdbcClient jdbc;

  public OrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<StoredOrder> findByClientOrderId(long userId, UUID clientOrderId) {
    return jdbc.sql(FIND_BY_CLIENT_ORDER_ID)
        .param("userId", userId)
        .param("clientOrderId", clientOrderId)
        .query(ORDER_MAPPER)
        .optional();
  }

  public Optional<StoredOrder> findById(long userId, long orderId) {
    return jdbc.sql(FIND_BY_ID)
        .param("userId", userId)
        .param("orderId", orderId)
        .query(ORDER_MAPPER)
        .optional();
  }

  /**
   * The newest FILLED or NEW order of the user that came from an equal request with another client
   * order id and was recorded within the window, if there is one. Both times are of the clock of
   * the database.
   */
  public Optional<Long> findRecentEqualOrderId(
      long userId, UUID clientOrderId, String requestFingerprint, Duration window) {
    return jdbc.sql(FIND_RECENT_EQUAL_ORDER_ID)
        .param("userId", userId)
        .param("windowMillis", window.toMillis())
        .param("requestFingerprint", requestFingerprint)
        .param("clientOrderId", clientOrderId)
        .param("acceptedStatuses", ACCEPTED_STATUSES)
        .query(Long.class)
        .optional();
  }

  /**
   * The newest orders of the user first, from after the key if there is one, filtered by status if
   * there is one. At most {@code limit} orders.
   */
  public List<StoredOrder> findPage(
      long userId, @Nullable OrderStatus status, @Nullable OrderSortKey after, int limit) {
    String sql =
        SELECT
            + PAGE_FROM
            + (status == null ? "" : PAGE_STATUS_CLAUSE)
            + (after == null ? "" : PAGE_AFTER_CLAUSE)
            + PAGE_ORDER;
    JdbcClient.StatementSpec statement =
        jdbc.sql(sql).param("userId", userId).param("limit", limit);
    if (status != null) {
      statement = statement.param("status", status.name());
    }
    if (after != null) {
      statement =
          statement.param("afterDatetime", after.createdAt()).param("afterId", after.orderId());
    }
    return statement.query(ORDER_MAPPER).list();
  }

  /** Cancels the order if it is the user's and still NEW; empty when no row changed. */
  public Optional<StoredOrder> cancelIfNew(long userId, long orderId) {
    return jdbc.sql(CANCEL_IF_NEW)
        .param("userId", userId)
        .param("orderId", orderId)
        .param("cancelledStatus", OrderStatus.CANCELLED.name())
        .param("newStatus", OrderStatus.NEW.name())
        .query(ORDER_MAPPER)
        .optional();
  }

  /**
   * Records the order. A client order id that the user already used fails with {@link
   * org.springframework.dao.DuplicateKeyException}.
   */
  public StoredOrder insert(NewOrder order) {
    return jdbc.sql(INSERT)
        .param("userId", order.userId())
        .param("instrumentId", order.instrumentId())
        .param("side", order.side().name())
        .param("type", order.type().name())
        .param("size", order.size())
        .param("price", order.price())
        .param("status", order.status().name())
        .param("clientOrderId", order.clientOrderId())
        .param("requestFingerprint", order.requestFingerprint())
        .param(
            "rejectionReason",
            order.rejectionReason() == null ? null : order.rejectionReason().name())
        .query(ORDER_MAPPER)
        .single();
  }

  private static StoredOrder toOrder(ResultSet rs, int rowNum) throws SQLException {
    String rejectionReason = rs.getString("rejectionreason");
    return new StoredOrder(
        rs.getLong("id"),
        rs.getLong("userid"),
        rs.getLong("instrumentid"),
        rs.getString("ticker"),
        OrderSide.valueOf(rs.getString("side")),
        OrderType.valueOf(rs.getString("type")),
        rs.getInt("size"),
        rs.getBigDecimal("price"),
        OrderStatus.valueOf(rs.getString("status")),
        rs.getObject("clientorderid", UUID.class),
        rs.getString("requestfingerprint"),
        rejectionReason == null ? null : RejectionReason.valueOf(rejectionReason),
        rs.getObject("datetime", OffsetDateTime.class));
  }
}
