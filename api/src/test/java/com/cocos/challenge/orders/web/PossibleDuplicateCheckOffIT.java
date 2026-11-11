package com.cocos.challenge.orders.web;

import static com.cocos.challenge.support.OrderRequests.marketBuy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.cocos.challenge.orders.repository.OrderRepository;
import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The check for possible duplicates turned off, with a window of zero (RFC 5.5). A class of its
 * own: the setting is for the whole Spring context.
 */
@IntegrationTest
@TestPropertySource(properties = "cocos.orders.duplicates.window=0s")
class PossibleDuplicateCheckOffIT {

  private static final long USER_ONE = 1;
  private static final String QUANTITY_TEN = "\"quantity\": 10";

  @LocalServerPort private int port;
  @MockitoSpyBean private OrderRepository orders;

  private ApiResponse buyTenPamp() {
    return new ApiClient(port)
        .post("/users/" + USER_ONE + "/orders", marketBuy(UUID.randomUUID(), QUANTITY_TEN));
  }

  @Test
  void acceptsTheSameOrderRightAwayWithoutLookingForAnEarlierOne() {
    ApiResponse first = buyTenPamp();

    ApiResponse second = buyTenPamp();

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(201);
    assertThat(second.json().get("status").asString()).isEqualTo("FILLED");
    verify(orders, never())
        .findRecentEqualOrderId(anyLong(), any(UUID.class), anyString(), any(Duration.class));
  }
}
