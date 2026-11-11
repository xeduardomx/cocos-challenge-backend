package com.cocos.challenge.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DuplicateOrderPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void acceptsAnyWindowFromZeroWhichTurnsTheCheckOff() {
    assertThat(validator.validate(new DuplicateOrderProperties(Duration.ZERO))).isEmpty();
    assertThat(validator.validate(new DuplicateOrderProperties(Duration.ofSeconds(5)))).isEmpty();
  }

  @Test
  void isOffWithAWindowOfZeroAndOnWithAnyOther() {
    assertThat(new DuplicateOrderProperties(Duration.ZERO).enabled()).isFalse();
    assertThat(new DuplicateOrderProperties(Duration.ofMillis(1)).enabled()).isTrue();
  }

  @Test
  void rejectsANegativeWindow() {
    assertThat(validator.validate(new DuplicateOrderProperties(Duration.ofMillis(-1)))).hasSize(1);
  }
}
