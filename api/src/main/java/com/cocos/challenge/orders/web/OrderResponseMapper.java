package com.cocos.challenge.orders.web;

import com.cocos.challenge.orders.RejectionReason;
import com.cocos.challenge.orders.StoredOrder;
import com.cocos.challenge.shared.display.DisplayFormatter;
import com.cocos.challenge.shared.display.DisplayProperties;
import com.cocos.challenge.shared.domain.Decimals;
import java.math.BigDecimal;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** Turns a stored order into its response: the amount, the rejection text and the display zone. */
@Component
public class OrderResponseMapper {

  private final MessageSource messages;
  private final DisplayFormatter formatter;
  private final Locale locale;

  public OrderResponseMapper(
      MessageSource messages, DisplayFormatter formatter, DisplayProperties display) {
    this.messages = messages;
    this.formatter = formatter;
    this.locale = display.locale();
  }

  public OrderResponse toResponse(StoredOrder order) {
    boolean cash = !order.side().isTrade();
    return new OrderResponse(
        order.id(),
        order.clientOrderId(),
        order.instrumentId(),
        order.ticker(),
        order.side(),
        order.type(),
        cash ? null : order.size(),
        cash ? null : order.price(),
        amount(order),
        order.status(),
        order.rejectionReason(),
        rejectionMessage(order.rejectionReason()),
        formatter.inDisplayZone(order.createdAt()));
  }

  private static @Nullable BigDecimal amount(StoredOrder order) {
    BigDecimal price = order.price();
    if (price == null) {
      return null;
    }
    return Decimals.roundMoney(price.multiply(BigDecimal.valueOf(order.size())));
  }

  private @Nullable String rejectionMessage(@Nullable RejectionReason reason) {
    if (reason == null) {
      return null;
    }
    return messages.getMessage(reason.messageKey(), null, locale);
  }
}
