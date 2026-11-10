package com.cocos.challenge.orders.listing;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Where a page ended: the date, with its microseconds, and the id of its last order. The client
 * sees it as opaque text: {@code Instant|id} in base64url without padding.
 */
public record OrderCursor(OffsetDateTime createdAt, long orderId) {

  private static final char SEPARATOR = '|';
  private static final Instant EARLIEST_CURSOR_TIME = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant LATEST_CURSOR_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

  public String encode() {
    String content = createdAt.toInstant().toString() + SEPARATOR + orderId;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads the text that {@link #encode()} wrote; any other text is an invalid argument. A valid
   * cursor always comes from a stored order, and Postgres does not hold the whole range of {@link
   * Instant}, so a time outside the years 1 to 9999 is rejected here instead of failing in the
   * query.
   */
  public static OrderCursor decode(String text) {
    String content = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    int separator = content.indexOf(SEPARATOR);
    if (separator < 0) {
      throw new IllegalArgumentException("The cursor has no separator");
    }
    try {
      Instant createdAt = Instant.parse(content.substring(0, separator));
      if (createdAt.isBefore(EARLIEST_CURSOR_TIME) || createdAt.isAfter(LATEST_CURSOR_TIME)) {
        throw new IllegalArgumentException("The cursor date is out of range");
      }
      long orderId = Long.parseLong(content.substring(separator + 1));
      return new OrderCursor(createdAt.atOffset(ZoneOffset.UTC), orderId);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("The cursor has no valid date", e);
    }
  }
}
