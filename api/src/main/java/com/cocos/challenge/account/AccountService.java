package com.cocos.challenge.account;

import java.sql.SQLException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Loads the account of a user and takes the turn of a user to submit an order (RFC 5.2). */
@Service
public class AccountService {

  /** The Postgres state of a lock wait that ran out; Spring does not map it to its own type. */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  private final AccountRepository repository;
  private final AccountCalculator calculator;
  private final AccountProperties properties;

  public AccountService(
      AccountRepository repository, AccountCalculator calculator, AccountProperties properties) {
    this.repository = repository;
    this.calculator = calculator;
    this.properties = properties;
  }

  public Account load(long userId) {
    ensureExists(userId);
    return calculator.calculate(repository.findEntries(userId));
  }

  public void ensureExists(long userId) {
    if (!repository.exists(userId)) {
      throw new UserNotFoundException(userId);
    }
  }

  /**
   * Locks the user until the transaction ends, so its orders go one at a time. Needs an open
   * transaction: the lock and its maximum wait last as long as the transaction does.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void takeTurn(long userId) {
    try {
      if (!repository.lockForTurn(userId, properties.turnTimeout())) {
        throw new UserNotFoundException(userId);
      }
    } catch (UncategorizedSQLException e) {
      if (e.getCause() instanceof SQLException cause
          && LOCK_NOT_AVAILABLE.equals(cause.getSQLState())) {
        throw new UserBusyException();
      }
      throw e;
    }
  }
}
