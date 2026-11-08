package com.cocos.challenge.orders.submission;

import com.cocos.challenge.instruments.Instrument;
import com.cocos.challenge.instruments.MarketPrice;
import com.cocos.challenge.orders.OrderLimits;
import com.cocos.challenge.orders.OrderType;
import com.cocos.challenge.shared.display.DisplayFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Works out the quantity and the price to record. By amount, the quantity is the amount divided by
 * the price, cut to whole shares: the order never spends more than the amount.
 */
public class QuantityResolver {

  private static final String CURRENCY_KEY = "order.instrument-is-currency";
  private static final String QUANTITY_ZERO_KEY = "order.quantity-zero";
  private static final String QUANTITY_TOO_LARGE_KEY = "order.quantity-too-large";
  private static final String NO_PRICE_KEY = "order.no-price-for-amount";
  private static final String MISSING_QUANTITY_AND_AMOUNT = "quantity or amount is required";
  private static final BigDecimal MAX_QUANTITY = BigDecimal.valueOf(OrderLimits.MAX_QUANTITY);

  private final DisplayFormatter formatter;

  public QuantityResolver(DisplayFormatter formatter) {
    this.formatter = formatter;
  }

  public ResolvedOrder resolve(
      SubmitOrderCommand command, Instrument instrument, @Nullable MarketPrice marketPrice) {
    if (instrument.isCurrency()) {
      throw new InvalidOrderException(CURRENCY_KEY);
    }
    BigDecimal price = recordedPrice(command, marketPrice);
    Integer quantity = command.quantity();
    if (quantity != null) {
      return new ResolvedOrder(quantity, price);
    }
    if (price == null) {
      throw new InvalidOrderException(NO_PRICE_KEY, instrument.ticker());
    }
    BigDecimal amount = Objects.requireNonNull(command.amount(), MISSING_QUANTITY_AND_AMOUNT);
    return new ResolvedOrder(quantityFor(amount, price, instrument), price);
  }

  /** The limit of a limit order, the last close of a market order, or null without a quote. */
  private static @Nullable BigDecimal recordedPrice(
      SubmitOrderCommand command, @Nullable MarketPrice marketPrice) {
    if (command.type() == OrderType.LIMIT) {
      return command.limitPrice();
    }
    return marketPrice == null ? null : marketPrice.close();
  }

  private int quantityFor(BigDecimal amount, BigDecimal price, Instrument instrument) {
    BigDecimal quantity = amount.divide(price, 0, RoundingMode.DOWN);
    if (quantity.compareTo(MAX_QUANTITY) > 0) {
      throw new InvalidOrderException(
          QUANTITY_TOO_LARGE_KEY, formatter.formatQuantity(OrderLimits.MAX_QUANTITY));
    }
    if (quantity.signum() == 0) {
      throw new InvalidOrderException(
          QUANTITY_ZERO_KEY,
          formatter.formatMoney(amount),
          instrument.ticker(),
          formatter.formatMoney(price));
    }
    return quantity.intValueExact();
  }
}
