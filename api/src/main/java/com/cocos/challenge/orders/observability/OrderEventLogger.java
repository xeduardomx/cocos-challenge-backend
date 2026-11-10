package com.cocos.challenge.orders.observability;

import com.cocos.challenge.orders.domain.StoredOrder;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Writes one line per order the API processed or cancelled, with its data as fields (RFC 8.1). */
@Component
public class OrderEventLogger {

  private static final Logger LOG = LoggerFactory.getLogger(OrderEventLogger.class);

  private static final String USER_ID_KEY = "userId";
  private static final String ORDER_ID_KEY = "orderId";
  private static final String CLIENT_ORDER_ID_KEY = "clientOrderId";
  private static final String SIDE_KEY = "side";
  private static final String TYPE_KEY = "type";
  private static final String STATUS_KEY = "status";
  private static final String REJECTION_REASON_KEY = "rejectionReason";

  private static final String PROCESSED_MESSAGE = "Order processed";
  private static final String REPLAYED_MESSAGE = "Order replayed";
  private static final String CANCELLED_MESSAGE = "Order cancelled";

  public void logProcessed(StoredOrder order, boolean replayed) {
    writeLine(order, replayed ? REPLAYED_MESSAGE : PROCESSED_MESSAGE);
  }

  public void logCancelled(StoredOrder order) {
    writeLine(order, CANCELLED_MESSAGE);
  }

  private static void writeLine(StoredOrder order, String message) {
    Map<String, String> fields = fieldsOf(order);
    try {
      fields.forEach(MDC::put);
      LOG.info(message);
    } finally {
      fields.keySet().forEach(MDC::remove);
    }
  }

  /** What an order does not have stays out: an order older than the API has no identifier. */
  private static Map<String, String> fieldsOf(StoredOrder order) {
    Map<String, String> fields = new HashMap<>();
    fields.put(USER_ID_KEY, String.valueOf(order.userId()));
    fields.put(ORDER_ID_KEY, String.valueOf(order.id()));
    fields.put(SIDE_KEY, order.side().name());
    fields.put(TYPE_KEY, order.type().name());
    fields.put(STATUS_KEY, order.status().name());
    if (order.clientOrderId() != null) {
      fields.put(CLIENT_ORDER_ID_KEY, order.clientOrderId().toString());
    }
    if (order.rejectionReason() != null) {
      fields.put(REJECTION_REASON_KEY, order.rejectionReason().name());
    }
    return fields;
  }
}
