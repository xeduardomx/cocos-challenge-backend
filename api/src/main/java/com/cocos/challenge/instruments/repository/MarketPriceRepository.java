package com.cocos.challenge.instruments.repository;

import com.cocos.challenge.instruments.domain.MarketPrice;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MarketPriceRepository {

  /** One index lookup per instrument: the newest row of each (RFC 4.2). */
  private static final String FIND_LATEST =
      """
      select i.id, m.close, m.previousclose
      from unnest(cast(:ids as bigint[])) as i(id)
      cross join lateral (
        select close, previousclose from marketdata
        where instrumentid = i.id
        order by date desc
        limit 1
      ) m
      """;

  private final JdbcClient jdbc;

  public MarketPriceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The latest price of each instrument that has one, by instrument id. */
  public Map<Long, MarketPrice> findLatest(Collection<Long> instrumentIds) {
    Long[] ids = instrumentIds.toArray(Long[]::new);
    Map<Long, MarketPrice> prices = new LinkedHashMap<>();
    jdbc.sql(FIND_LATEST)
        .param("ids", ids)
        .query((rs, rowNum) ->
            new MarketPrice(
                rs.getLong("id"), rs.getBigDecimal("close"), rs.getBigDecimal("previousclose")))
        .list()
        .forEach(price -> prices.put(price.instrumentId(), price));
    return prices;
  }
}
