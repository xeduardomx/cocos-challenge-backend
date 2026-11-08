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
}
