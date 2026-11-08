package com.cocos.challenge.instruments;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The single entry point other functions use to read instruments and their latest price. */
@Service
public class InstrumentCatalog {

  private final InstrumentRepository instruments;
  private final MarketPriceRepository prices;

  public InstrumentCatalog(InstrumentRepository instruments, MarketPriceRepository prices) {
    this.instruments = instruments;
    this.prices = prices;
  }

  public Optional<Instrument> find(long id) {
    return instruments.findById(id);
  }

  public Map<Long, Instrument> findAll(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return instruments.findAllById(ids).stream()
        .collect(Collectors.toMap(Instrument::id, Function.identity()));
  }

  public Optional<MarketPrice> findLatestPrice(long instrumentId) {
    return Optional.ofNullable(findLatestPrices(List.of(instrumentId)).get(instrumentId));
  }

  public Map<Long, MarketPrice> findLatestPrices(Collection<Long> instrumentIds) {
    if (instrumentIds.isEmpty()) {
      return Map.of();
    }
    return prices.findLatest(instrumentIds);
  }
}
