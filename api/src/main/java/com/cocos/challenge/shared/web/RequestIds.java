package com.cocos.challenge.shared.web;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** The id of each request: where it travels and which values are accepted. */
public final class RequestIds {

  public static final String HEADER = "X-Request-Id";
  public static final String MDC_KEY = "requestId";
  public static final int MAX_LENGTH = 64;
  public static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9-]+");

  private RequestIds() {}

  /** The id of the request being served, or null outside of one. */
  public static @Nullable String current() {
    return MDC.get(MDC_KEY);
  }
}
