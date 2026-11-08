package com.cocos.challenge.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MaxScaleValidatorTest {

  private static final int TWO_DECIMALS = 2;

  private final MaxScaleValidator validator = validatorFor(TWO_DECIMALS);
  private final ConstraintValidatorContext context = Mockito.mock(ConstraintValidatorContext.class);

  private static MaxScaleValidator validatorFor(int scale) {
    MaxScale constraint = Mockito.mock(MaxScale.class);
    Mockito.when(constraint.value()).thenReturn(scale);
    MaxScaleValidator validator = new MaxScaleValidator();
    validator.initialize(constraint);
    return validator;
  }

  @Test
  void acceptsTheAllowedNumberOfDecimals() {
    assertThat(validator.isValid(new BigDecimal("1.00"), context)).isTrue();
  }

  @Test
  void rejectsMoreDecimalsThanAllowed() {
    assertThat(validator.isValid(new BigDecimal("1.001"), context)).isFalse();
  }

  @Test
  void acceptsAnExponentWithNoDecimals() {
    assertThat(validator.isValid(new BigDecimal("1E+3"), context)).isTrue();
  }

  @Test
  void ignoresTrailingZeros() {
    assertThat(validator.isValid(new BigDecimal("1.5000"), context)).isTrue();
  }

  @Test
  void acceptsAHugeExponentWithZerosInTheMantissaWithoutOverflowing() {
    assertThat(validator.isValid(new BigDecimal("100e2147483647"), context)).isTrue();
  }

  @Test
  void acceptsNull() {
    assertThat(validator.isValid(null, context)).isTrue();
  }
}
