package com.cocos.challenge.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The number has at most {@link #value()} decimals. Trailing zeros do not count. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MaxScaleValidator.class)
public @interface MaxScale {

  int value();

  String message();

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
