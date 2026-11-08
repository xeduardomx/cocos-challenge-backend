package com.cocos.challenge.account;

import com.cocos.challenge.shared.domain.OrderSide;
import com.cocos.challenge.shared.domain.OrderStatus;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

  private static final String TIMEOUT_UNIT = "ms";
  private static final String SET_LOCK_TIMEOUT =
      "select set_config('lock_timeout', :timeout, true)";
  private static final String LOCK_USER = "select id from users where id = :userId for update";
  private static final String USER_EXISTS =
      "select exists (select 1 from users where id = :userId)";
  private static final String FIND_ENTRIES =
      """
      select instrumentid, side, status, size, price from orders
      where userid = :userId and status in (:statuses)
      order by datetime, id
      """;

  private static final List<String> ACCOUNT_STATUSES =
      List.of(OrderStatus.FILLED.name(), OrderStatus.NEW.name());

  private static final RowMapper<AccountEntry> ENTRY_MAPPER =
      (rs, rowNum) ->
          new AccountEntry(
              rs.getLong("instrumentid"),
              OrderSide.valueOf(rs.getString("side")),
              OrderStatus.valueOf(rs.getString("status")),
              rs.getInt("size"),
              rs.getBigDecimal("price"));

  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean exists(long userId) {
    return jdbc.sql(USER_EXISTS).param("userId", userId).query(Boolean.class).single();
  }

  /**
   * Locks the row of the user until the end of the current transaction, waiting at most {@code
   * timeout}. Returns false if the user does not exist. When the wait runs out, the database fails
   * with SQL state 55P03.
   */
  public boolean lockForTurn(long userId, Duration timeout) {
    jdbc.sql(SET_LOCK_TIMEOUT)
        .param("timeout", timeout.toMillis() + TIMEOUT_UNIT)
        .query()
        .singleRow();
    return jdbc.sql(LOCK_USER).param("userId", userId).query(Long.class).optional().isPresent();
  }

  /** The FILLED and NEW orders of the user, oldest first. */
  public List<AccountEntry> findEntries(long userId) {
    return jdbc.sql(FIND_ENTRIES)
        .param("userId", userId)
        .param("statuses", ACCOUNT_STATUSES)
        .query(ENTRY_MAPPER)
        .list();
  }
}
