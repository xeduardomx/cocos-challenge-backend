package com.cocos.challenge.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.account.domain.Account;
import com.cocos.challenge.account.domain.Position;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.MarketPrice;
import com.cocos.challenge.shared.domain.Decimals;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortfolioValuatorTest {

  private static final long BMA = 31;
  private static final long METR = 54;
  private static final long PAMP = 47;
  private static final long PGR = 3;

  private static final Map<Long, Instrument> INSTRUMENTS =
      Map.of(
          BMA, new Instrument(BMA, "BMA", "Banco Macro S.A.", "ACCIONES"),
          METR, new Instrument(METR, "METR", "MetroGAS S.A.", "ACCIONES"),
          PAMP, new Instrument(PAMP, "PAMP", "Pampa Holding S.A.", "ACCIONES"),
          PGR, new Instrument(PGR, "PGR", "Phoenix Global Resources", "ACCIONES"));

  private static final Map<Long, MarketPrice> PRICES =
      Map.of(
          BMA, new MarketPrice(BMA, new BigDecimal("1502.80"), new BigDecimal("1520.30")),
          METR, new MarketPrice(METR, new BigDecimal("229.50"), new BigDecimal("232.00")),
          PAMP, new MarketPrice(PAMP, new BigDecimal("925.85"), new BigDecimal("921.80")));

  private final PortfolioValuator valuator = new PortfolioValuator();

  private static Account user1() {
    return new Account(
        new BigDecimal("753000.00"),
        new BigDecimal("125500.00"),
        Map.of(
            PAMP, new Position(PAMP, 40, 0, new BigDecimal("930.00")),
            METR, new Position(METR, 500, 0, new BigDecimal("250.00")),
            BMA, new Position(BMA, -10, 0, null)));
  }

  private Portfolio value(Account account) {
    return valuator.value(account, INSTRUMENTS, PRICES);
  }

  private PositionValuation position(Portfolio portfolio, String ticker) {
    return portfolio.positions().stream()
        .filter(p -> p.ticker().equals(ticker))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void addsThePesosAndTheValueOfThePositions() {
    Portfolio portfolio = value(user1());

    assertThat(portfolio.totalValue()).isEqualByComparingTo("889756.00");
    assertThat(portfolio.cashBalance()).isEqualByComparingTo("753000.00");
    assertThat(portfolio.reservedCash()).isEqualByComparingTo("125500.00");
    assertThat(portfolio.availableCash()).isEqualByComparingTo("627500.00");
  }

  @Test
  void valuesAPositionWithItsReturnAndDailyChange() {
    PositionValuation metr = position(value(user1()), "METR");

    assertThat(metr.name()).isEqualTo("MetroGAS S.A.");
    assertThat(metr.quantity()).isEqualTo(500);
    assertThat(metr.lastPrice()).isEqualByComparingTo("229.50");
    assertThat(metr.marketValue()).isEqualByComparingTo("114750.00");
    assertThat(metr.averagePrice()).isEqualByComparingTo("250.00");
    assertThat(metr.totalReturnPercent()).isEqualByComparingTo("-8.20");
    assertThat(metr.dailyChangePercent()).isEqualByComparingTo("-1.08");

    PositionValuation pamp = position(value(user1()), "PAMP");
    assertThat(pamp.marketValue()).isEqualByComparingTo("37034.00");
    assertThat(pamp.totalReturnPercent()).isEqualByComparingTo("-0.45");
    assertThat(pamp.dailyChangePercent()).isEqualByComparingTo("0.44");
  }

  @Test
  void valuesAShortPositionNegativeAndWithoutAverageOrReturn() {
    PositionValuation bma = position(value(user1()), "BMA");

    assertThat(bma.marketValue()).isEqualByComparingTo("-15028.00");
    assertThat(bma.averagePrice()).isNull();
    assertThat(bma.totalReturnPercent()).isNull();
    assertThat(bma.dailyChangePercent()).isEqualByComparingTo("-1.15");
  }

  @Test
  void sortsThePositionsByTicker() {
    assertThat(value(user1()).positions())
        .extracting(PositionValuation::ticker)
        .containsExactly("BMA", "METR", "PAMP");
  }

  @Test
  void showsTheAveragePriceRoundedButCalculatesTheReturnWithTheExactOne() {
    Account account =
        new Account(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Map.of(METR, new Position(METR, 3, 0, new BigDecimal("250.004"))));

    PositionValuation metr = position(value(account), "METR");

    assertThat(metr.averagePrice()).isEqualTo(new BigDecimal("250.00"));
    // (229.50 - 250.004) / 250.004 = -8.2013...
    assertThat(metr.totalReturnPercent()).isEqualByComparingTo("-8.20");
  }

  @Test
  void valuesAPositionWithoutPriceAsEmpty() {
    Account account =
        new Account(
            new BigDecimal("1000.00"),
            BigDecimal.ZERO,
            Map.of(PGR, new Position(PGR, 5, 2, new BigDecimal("100.00"))));

    Portfolio portfolio = value(account);
    PositionValuation pgr = position(portfolio, "PGR");

    assertThat(pgr.lastPrice()).isNull();
    assertThat(pgr.marketValue()).isNull();
    assertThat(pgr.totalReturnPercent()).isNull();
    assertThat(pgr.dailyChangePercent()).isNull();
    assertThat(pgr.reservedQuantity()).isEqualTo(2);
    assertThat(portfolio.totalValue()).isEqualByComparingTo("1000.00");
  }

  @Test
  void showsAnEmptyPortfolioAsZeroPesos() {
    Portfolio portfolio = value(new Account(Decimals.ZERO_MONEY, Decimals.ZERO_MONEY, Map.of()));

    assertThat(portfolio.totalValue()).isEqualTo("0.00");
    assertThat(portfolio.availableCash()).isEqualTo("0.00");
    assertThat(portfolio.positions()).isEmpty();
  }
}
