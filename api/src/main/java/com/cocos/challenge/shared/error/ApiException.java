package com.cocos.challenge.shared.error;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * An error the API reports as a Problem Details response. It carries the problem type, the key of
 * its text and the data; the handler builds the HTTP response.
 */
public abstract class ApiException extends RuntimeException {

  private final ProblemType problemType;
  private final String detailKey;
  private final String[] detailArgs;

  protected ApiException(ProblemType problemType, String detailKey, String... detailArgs) {
    super(detailKey);
    this.problemType = problemType;
    this.detailKey = detailKey;
    this.detailArgs = detailArgs.clone();
  }

  public ProblemType problemType() {
    return problemType;
  }

  public String detailKey() {
    return detailKey;
  }

  public String[] detailArgs() {
    return detailArgs.clone();
  }

  /** Extra members of the problem, such as {@code currentStatus}. */
  public Map<String, Object> properties() {
    return Map.of();
  }

  /** How long the client should wait before retrying, if it makes sense to retry. */
  public Optional<Duration> retryAfter() {
    return Optional.empty();
  }
}
