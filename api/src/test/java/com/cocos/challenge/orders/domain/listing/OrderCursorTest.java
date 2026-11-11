package com.cocos.challenge.orders.domain.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderCursorTest {

  private static final long ORDER_ID = 12;

  @Test
  void isReadBackWithItsMicroseconds() {
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-08T14:02:15.483921Z");

    OrderCursor read = OrderCursor.decode(new OrderCursor(createdAt, ORDER_ID).encode());

    assertThat(read.createdAt()).isEqualTo(createdAt);
    assertThat(read.orderId()).isEqualTo(ORDER_ID);
  }

  @Test
  void isUrlSafeText() {
    String encoded =
        new OrderCursor(OffsetDateTime.parse("2026-11-08T14:02:15.483921Z"), ORDER_ID).encode();

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @ParameterizedTest
  @ValueSource(strings = {"hello", "", "!!!", "%%%"})
  void rejectsTextThatIsNotBase64(String text) {
    assertThatThrownBy(() -> OrderCursor.decode(text)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "no separator",
        "not-a-date|12",
        "2026-11-08T14:02:15.483921Z|twelve",
        "2026-11-08T14:02:15.483921Z|",
        "|12",
        "+999999999-12-31T23:59:59Z|1",
        "-999999999-01-01T00:00:00Z|1"
      })
  void rejectsValidBase64WithOtherContent(String content) {
    String text =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(content.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> OrderCursor.decode(text)).isInstanceOf(IllegalArgumentException.class);
  }
}
