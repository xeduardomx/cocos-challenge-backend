package com.cocos.challenge.account.error;

import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;
import java.time.Duration;
import java.util.Optional;

/** The user is processing another order and the wait for the turn ran out. */
public class UserBusyException extends ApiException {

  public static final Duration RETRY_AFTER = Duration.ofSeconds(1);

  private static final String DETAIL_KEY = "user.busy";

  public UserBusyException() {
    super(ProblemType.USER_BUSY, DETAIL_KEY);
  }

  @Override
  public Optional<Duration> retryAfter() {
    return Optional.of(RETRY_AFTER);
  }
}
