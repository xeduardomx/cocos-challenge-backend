package com.cocos.challenge.orders.listing;

import com.cocos.challenge.account.AccountService;
import com.cocos.challenge.orders.OrderRepository;
import com.cocos.challenge.orders.OrderSortKey;
import com.cocos.challenge.orders.StoredOrder;
import com.cocos.challenge.shared.domain.OrderStatus;
import com.cocos.challenge.shared.error.InvalidRequestException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Lists the orders of a user, newest first, a page at a time (RFC 4.4). */
@Service
public class OrderQueryService {

  private static final String LIMIT_FIELD = "limit";
  private static final String CURSOR_FIELD = "cursor";
  private static final String LIMIT_OUT_OF_RANGE_KEY = "order-list.limit-out-of-range";
  private static final String CURSOR_INVALID_KEY = "order-list.cursor-invalid";
  private static final int SENTINEL = 1;

  private final AccountService accounts;
  private final OrderRepository orders;
  private final OrderPageProperties properties;

  public OrderQueryService(
      AccountService accounts, OrderRepository orders, OrderPageProperties properties) {
    this.accounts = accounts;
    this.orders = orders;
    this.properties = properties;
  }

  public OrderPage list(
      long userId, @Nullable OrderStatus status, @Nullable Integer limit, @Nullable String cursor) {
    accounts.ensureExists(userId);
    int size = pageSize(limit);
    OrderSortKey after = cursor == null ? null : sortKeyIn(cursor);
    // one more than the page: if it arrives, there is a next page (A-7)
    List<StoredOrder> found = orders.findPage(userId, status, after, size + SENTINEL);
    if (found.size() <= size) {
      return new OrderPage(found, null);
    }
    List<StoredOrder> page = found.subList(0, size);
    StoredOrder last = page.get(size - 1);
    return new OrderPage(page, new OrderCursor(last.createdAt(), last.id()).encode());
  }

  private int pageSize(@Nullable Integer limit) {
    if (limit == null) {
      return properties.defaultSize();
    }
    if (limit < 1 || limit > properties.maxSize()) {
      throw new InvalidRequestException(
          LIMIT_FIELD, LIMIT_OUT_OF_RANGE_KEY, String.valueOf(properties.maxSize()));
    }
    return limit;
  }

  /** The cursor is what the client sees; the repository only needs the key it carries. */
  private static OrderSortKey sortKeyIn(String cursor) {
    OrderCursor decoded;
    try {
      decoded = OrderCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(CURSOR_FIELD, CURSOR_INVALID_KEY);
    }
    return new OrderSortKey(decoded.createdAt(), decoded.orderId());
  }
}
