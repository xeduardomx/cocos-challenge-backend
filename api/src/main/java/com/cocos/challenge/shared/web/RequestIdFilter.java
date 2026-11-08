package com.cocos.challenge.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the client's {@code X-Request-Id} when it is safe, or a new UUID. The
 * id goes to the log context and to the response. It does nothing that can fail (RFC 7.3).
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId = acceptedOrNew(request.getHeader(RequestIds.HEADER));
    MDC.put(RequestIds.MDC_KEY, requestId);
    response.setHeader(RequestIds.HEADER, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(RequestIds.MDC_KEY);
    }
  }

  private static String acceptedOrNew(@Nullable String clientValue) {
    if (clientValue != null
        && clientValue.length() <= RequestIds.MAX_LENGTH
        && RequestIds.ALLOWED_CHARACTERS.matcher(clientValue).matches()) {
      return clientValue;
    }
    return UUID.randomUUID().toString();
  }
}
