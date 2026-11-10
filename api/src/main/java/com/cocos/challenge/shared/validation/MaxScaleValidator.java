package com.cocos.challenge.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

public class MaxScaleValidator implements ConstraintValidator<MaxScale, BigDecimal> {

  private int maxScale;

  @Override
  public void initialize(MaxScale constraint) {
    this.maxScale = constraint.value();
  }

  @Override
  public boolean isValid(@Nullable BigDecimal value, ConstraintValidatorContext context) {
    // stripTrailingZeros overflows on a huge exponent such as 100e2147483647, so it only runs when
    // the scale does not fit: then the scale is positive and stripping cannot overflow
    return value == null
        || value.scale() <= maxScale
        || value.stripTrailingZeros().scale() <= maxScale;
  }
}
