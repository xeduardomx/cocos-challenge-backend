package com.cocos.challenge.shared.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/** The catalog of problems the API reports (RFC 7.3). */
public enum ProblemType {
  VALIDATION_ERROR("validation-error", HttpStatus.BAD_REQUEST),
  INVALID_ORDER("invalid-order", HttpStatus.BAD_REQUEST),
  USER_NOT_FOUND("user-not-found", HttpStatus.NOT_FOUND),
  ORDER_NOT_FOUND("order-not-found", HttpStatus.NOT_FOUND),
  ORDER_NOT_CANCELLABLE("order-not-cancellable", HttpStatus.CONFLICT),
  CLIENT_ORDER_ID_REUSED("client-order-id-reused", HttpStatus.UNPROCESSABLE_CONTENT),
  USER_BUSY("user-busy", HttpStatus.SERVICE_UNAVAILABLE),
  INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR);

  private static final String TYPE_PATH_PREFIX = "/problems/";
  private static final String TITLE_KEY_PREFIX = "problem.";
  private static final String TITLE_KEY_SUFFIX = ".title";

  private final String slug;
  private final HttpStatus status;

  ProblemType(String slug, HttpStatus status) {
    this.slug = slug;
    this.status = status;
  }

  public URI uri() {
    return URI.create(TYPE_PATH_PREFIX + slug);
  }

  public HttpStatus status() {
    return status;
  }

  public String titleKey() {
    return TITLE_KEY_PREFIX + slug + TITLE_KEY_SUFFIX;
  }
}
