package com.cocos.challenge.orders.web;

import com.cocos.challenge.orders.domain.OrderType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

/**
 * Checks that the fields of an order request agree with each other, and reports each case on its
 * own field. It does not repeat what {@code @NotNull} reports.
 */
public class ConsistentOrderRequestValidator
    implements ConstraintValidator<ConsistentOrderRequest, SubmitOrderRequest> {

  private static final String QUANTITY = "quantity";
  private static final String PRICE = "price";

  private static final String QUANTITY_AND_AMOUNT = "{order.quantity-and-amount}";
  private static final String QUANTITY_OR_AMOUNT_MISSING = "{order.quantity-or-amount-missing}";
  private static final String LIMIT_NEEDS_PRICE = "{order.limit-needs-price}";
  private static final String MARKET_HAS_PRICE = "{order.market-has-price}";

  @Override
  public boolean isValid(@Nullable SubmitOrderRequest request, ConstraintValidatorContext context) {
    if (request == null) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    boolean valid = true;
    if (request.quantity() != null && request.amount() != null) {
      valid = report(context, QUANTITY, QUANTITY_AND_AMOUNT);
    } else if (request.quantity() == null && request.amount() == null) {
      valid = report(context, QUANTITY, QUANTITY_OR_AMOUNT_MISSING);
    }
    if (request.type() == OrderType.LIMIT && request.price() == null) {
      valid = report(context, PRICE, LIMIT_NEEDS_PRICE);
    } else if (request.type() == OrderType.MARKET && request.price() != null) {
      valid = report(context, PRICE, MARKET_HAS_PRICE);
    }
    return valid;
  }

  private static boolean report(ConstraintValidatorContext context, String field, String template) {
    context
        .buildConstraintViolationWithTemplate(template)
        .addPropertyNode(field)
        .addConstraintViolation();
    return false;
  }
}
