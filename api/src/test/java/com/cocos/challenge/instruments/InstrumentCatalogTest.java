package com.cocos.challenge.instruments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;

class InstrumentCatalogTest {

  private final InstrumentRepository instruments = mock(InstrumentRepository.class);
  private final MarketPriceRepository prices = mock(MarketPriceRepository.class);
  private final InstrumentCatalog catalog = new InstrumentCatalog(instruments, prices);

  @Test
  void doesNotQueryTheDatabaseForNoIds() {
    assertThat(catalog.findLatestPrices(List.of())).isEmpty();
    assertThat(catalog.findAll(List.of())).isEmpty();

    verifyNoInteractions(instruments, prices);
  }
}
