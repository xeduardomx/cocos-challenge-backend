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
    return value == null || value.stripTrailingZeros().scale() <= maxScale;
  }
}
