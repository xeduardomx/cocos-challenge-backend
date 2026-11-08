package com.cocos.challenge.instruments.search;

import com.cocos.challenge.instruments.Instrument;
import com.cocos.challenge.instruments.InstrumentCatalog;
import com.cocos.challenge.instruments.MarketPrice;
import com.cocos.challenge.shared.error.InvalidRequestException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Searches the instruments by ticker or name and adds the latest price to each one. */
@Service
public class InstrumentSearchService {

  private static final String QUERY_FIELD = "query";
  private static final String BLANK_KEY = "search.query-blank";
  private static final String TOO_LONG_KEY = "search.query-too-long";

  private final InstrumentCatalog catalog;
  private final SearchProperties properties;

  public InstrumentSearchService(InstrumentCatalog catalog, SearchProperties properties) {
    this.catalog = catalog;
    this.properties = properties;
  }

  public List<SearchResult> search(@Nullable String query) {
    String text = validated(query);
    List<Instrument> found = catalog.search(text, properties.maxResults());
    Map<Long, MarketPrice> prices =
        catalog.findLatestPrices(found.stream().map(Instrument::id).toList());
    return found.stream()
        .map(instrument -> new SearchResult(instrument, prices.get(instrument.id())))
        .toList();
  }

  private String validated(@Nullable String query) {
    String text = query == null ? "" : query.strip();
    if (text.isEmpty()) {
      throw new InvalidRequestException(QUERY_FIELD, BLANK_KEY);
    }
    if (text.length() > properties.maxQueryLength()) {
      throw new InvalidRequestException(
          QUERY_FIELD, TOO_LONG_KEY, String.valueOf(properties.maxQueryLength()));
    }
    return text;
  }
}
