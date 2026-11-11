package com.cocos.challenge.portfolio.service;

import com.cocos.challenge.account.domain.Account;
import com.cocos.challenge.account.domain.Position;
import com.cocos.challenge.account.service.AccountService;
import com.cocos.challenge.instruments.service.InstrumentCatalog;
import com.cocos.challenge.portfolio.domain.Portfolio;
import com.cocos.challenge.portfolio.domain.PortfolioValuator;
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
