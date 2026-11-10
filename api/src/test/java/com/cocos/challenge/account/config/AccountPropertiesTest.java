package com.cocos.challenge.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AccountPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void acceptsAnyTimeoutOfAtLeastOneMillisecond() {
    assertThat(validator.validate(new AccountProperties(Duration.ofMillis(1)))).isEmpty();
  }

  @Test
  void rejectsATimeoutPostgresWouldTakeAsNoLimit() {
    assertThat(validator.validate(new AccountProperties(Duration.ZERO))).hasSize(1);
    assertThat(validator.validate(new AccountProperties(Duration.ofNanos(500_000)))).hasSize(1);
    assertThat(validator.validate(new AccountProperties(Duration.ofSeconds(-1)))).hasSize(1);
  }
}
