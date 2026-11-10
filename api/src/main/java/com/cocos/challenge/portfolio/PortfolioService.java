package com.cocos.challenge.portfolio;

import com.cocos.challenge.account.Account;
import com.cocos.challenge.account.AccountService;
import com.cocos.challenge.account.Position;
import com.cocos.challenge.instruments.InstrumentCatalog;
import java.util.List;
import org.springframework.stereotype.Service;

/** Loads the account of a user and its prices, and values them. */
@Service
public class PortfolioService {

  private final AccountService accounts;
  private final InstrumentCatalog catalog;
  private final PortfolioValuator valuator;

  public PortfolioService(
      AccountService accounts, InstrumentCatalog catalog, PortfolioValuator valuator) {
    this.accounts = accounts;
    this.catalog = catalog;
    this.valuator = valuator;
  }

  public Portfolio portfolioOf(long userId) {
    Account account = accounts.load(userId);
    List<Long> instrumentIds =
        account.heldPositions().stream().map(Position::instrumentId).toList();
    return valuator.value(
        account, catalog.findAll(instrumentIds), catalog.findLatestPrices(instrumentIds));
  }
}
