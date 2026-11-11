package com.cocos.challenge.instruments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class InstrumentRepositoryIT {

  private static final long PAMP = 47;
  private static final long ARS = 66;
  private static final long UNKNOWN = 999;

  @Autowired private InstrumentRepository repository;

  @Test
  void findsAnInstrumentById() {
    Instrument pamp = repository.findById(PAMP).orElseThrow();

    assertThat(pamp.ticker()).isEqualTo("PAMP");
    assertThat(pamp.name()).isEqualTo("Pampa Holding S.A.");
    assertThat(pamp.type()).isEqualTo("ACCIONES");
    assertThat(pamp.isCurrency()).isFalse();
  }

  @Test
  void recognizesTheCurrency() {
    assertThat(repository.findById(ARS).orElseThrow().isCurrency()).isTrue();
  }

  @Test
  void returnsEmptyForAnUnknownId() {
    assertThat(repository.findById(UNKNOWN)).isEmpty();
  }

  @Test
  void findsSeveralInstrumentsAtOnce() {
    assertThat(repository.findAllById(List.of(31L, PAMP, 54L))).hasSize(3);
  }
}
