package com.cocos.challenge.orders.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The fields of an order request agree with each other: a trade side, quantity or amount (not
 * both), and a price only in a limit order. Each violation names its own field and message.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = ConsistentOrderRequestValidator.class)
public @interface ConsistentOrderRequest {

  /** Required by Bean Validation, never shown: the validator builds the message of each case. */
  String message() default "";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
