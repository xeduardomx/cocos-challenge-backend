package com.cocos.challenge.instruments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.instruments.domain.MarketPrice;
import com.cocos.challenge.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class MarketPriceRepositoryIT {

  private static final long PAMP = 47;
  private static final long PGR = 3;
  private static final BigDecimal PAMP_CLOSE = new BigDecimal("925.85");
  private static final BigDecimal PAMP_PREVIOUS_CLOSE = new BigDecimal("921.80");

  @Autowired private MarketPriceRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void findsTheLatestCloseAndPreviousClose() {
    MarketPrice pamp = latestPamp();
    assertThat(pamp.close()).isEqualByComparingTo(PAMP_CLOSE);
    assertThat(pamp.previousClose()).isEqualByComparingTo(PAMP_PREVIOUS_CLOSE);
  }

  @Test
  void skipsAnInstrumentWithoutPrices() {
    Map<Long, MarketPrice> prices = repository.findLatest(List.of(PAMP, PGR));

    assertThat(prices).containsOnlyKeys(PAMP);
  }

  @Test
  void usesTheMostRecentDateNotTheLastLoadedRow() {
    jdbc.sql(
            "insert into marketdata (instrumentid, high, low, open, close, previousclose, date)"
                + " values (?, 1, 1, 1, 1, 1, date '2023-07-10')")
        .param(PAMP)
        .update();

    MarketPrice pamp = latestPamp();

    assertThat(pamp.close()).isEqualByComparingTo(PAMP_CLOSE);
  }

  private MarketPrice latestPamp() {
    return Objects.requireNonNull(repository.findLatest(List.of(PAMP)).get(PAMP));
  }
}
