package com.cocos.challenge.instruments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentRepository {

  private static final String SELECT = "select id, ticker, name, type from instruments";
  private static final String FIND_BY_ID = SELECT + " where id = :id";
  private static final String FIND_ALL_BY_ID = SELECT + " where id in (:ids)";

  private static final String SEARCH =
      """
      with q as (select lower(unaccent(trim(:text))) as t),
           words as (select w from q, regexp_split_to_table(q.t, '\\s+') w where w <> '')
      select i.id, i.ticker, i.name, i.type
      from instruments i, q
      where i.type <> :currencyType
        and exists (select 1 from words)
        and not exists (
          select 1 from words where strpos(lower(unaccent(i.ticker || ' ' || i.name)), w) = 0)
      order by case when lower(i.ticker) = q.t then 1
                    when starts_with(lower(i.ticker), q.t) then 2
                    when strpos(lower(i.ticker), q.t) > 0 then 3
                    when starts_with(lower(unaccent(i.name)), q.t) then 4
                    else 5 end,
               i.ticker collate "C"
      limit :limit
      """;

  private static final RowMapper<Instrument> INSTRUMENT_MAPPER =
      (rs, rowNum) ->
          new Instrument(
              rs.getLong("id"), rs.getString("ticker"), rs.getString("name"), rs.getString("type"));

  private final JdbcClient jdbc;

  public InstrumentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Instrument> findById(long id) {
    return jdbc.sql(FIND_BY_ID).param("id", id).query(INSTRUMENT_MAPPER).optional();
  }

  public List<Instrument> findAllById(Collection<Long> ids) {
    return jdbc.sql(FIND_ALL_BY_ID).param("ids", ids).query(INSTRUMENT_MAPPER).list();
  }

  /**
   * Instruments whose ticker or name has every word of the text, best match first (ticker equal,
   * ticker prefix, ticker contains, name prefix, the rest), then by ticker. The currency is
   * excluded.
   */
  public List<Instrument> search(String text, int limit) {
    return jdbc.sql(SEARCH)
        .param("text", text)
        .param("currencyType", Instrument.CURRENCY_TYPE)
        .param("limit", limit)
        .query(INSTRUMENT_MAPPER)
        .list();
  }
}
