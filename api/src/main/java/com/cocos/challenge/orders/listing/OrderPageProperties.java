package com.cocos.challenge.orders.listing;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The size of a page of orders: the one used when none is asked for, and the largest allowed. */
@Validated
@ConfigurationProperties("cocos.orders.page")
public record OrderPageProperties(@Positive int defaultSize, @Positive int maxSize) {

  public OrderPageProperties {
    if (defaultSize > maxSize) {
      throw new IllegalArgumentException(
          "The default page size ("
              + defaultSize
              + ") is larger than the maximum ("
              + maxSize
              + ")");
    }
  }
}
