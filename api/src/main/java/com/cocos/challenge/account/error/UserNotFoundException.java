package com.cocos.challenge.account.error;

import com.cocos.challenge.shared.error.ApiException;
import com.cocos.challenge.shared.error.ProblemType;

public class UserNotFoundException extends ApiException {

  private static final String DETAIL_KEY = "user.not-found";

  public UserNotFoundException(long userId) {
    super(ProblemType.USER_NOT_FOUND, DETAIL_KEY, String.valueOf(userId));
  }
}
