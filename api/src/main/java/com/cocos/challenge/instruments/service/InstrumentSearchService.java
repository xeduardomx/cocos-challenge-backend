package com.cocos.challenge.instruments.service;

import com.cocos.challenge.instruments.config.SearchProperties;
import com.cocos.challenge.instruments.domain.Instrument;
import com.cocos.challenge.instruments.domain.MarketPrice;
import com.cocos.challenge.instruments.domain.SearchResult;
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
  private static final String INVALID_CHARACTER_KEY = "search.query-invalid-character";

  /** Postgres does not accept this character in a text, so it never reaches the query. */
  private static final char NUL = '\0';

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
    if (text.indexOf(NUL) >= 0) {
      throw new InvalidRequestException(QUERY_FIELD, INVALID_CHARACTER_KEY);
    }
    return text;
  }
}
