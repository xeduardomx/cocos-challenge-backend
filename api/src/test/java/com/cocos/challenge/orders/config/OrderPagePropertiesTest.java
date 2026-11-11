package com.cocos.challenge.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderPagePropertiesTest {

  @Test
  void rejectsADefaultSizeLargerThanTheMaximum() {
    assertThatThrownBy(() -> new OrderPageProperties(200, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsADefaultSizeEqualToTheMaximum() {
    assertThat(new OrderPageProperties(100, 100).defaultSize()).isEqualTo(100);
  }
}
