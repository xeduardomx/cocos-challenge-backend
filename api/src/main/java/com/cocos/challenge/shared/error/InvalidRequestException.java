package com.cocos.challenge.shared.error;

/** A request value that is not valid, reported with the field it belongs to. */
public final class InvalidRequestException extends ApiException {

  private final String field;

  public InvalidRequestException(String field, String messageKey, String... args) {
    super(ProblemType.VALIDATION_ERROR, messageKey, args);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
