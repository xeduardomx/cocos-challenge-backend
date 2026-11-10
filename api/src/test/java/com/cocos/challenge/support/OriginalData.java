package com.cocos.challenge.support;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Brings the test database back to the data of {@code database.sql}. The first call stores a
 * snapshot of the four tables, after the migrations; every call empties the tables, refills them
 * from the snapshot and leaves each id counter at the highest id of the snapshot.
 */
public final class OriginalData {

  private static final String SNAPSHOT_SCHEMA = "originals";
  private static final List<String> TABLES =
      List.of("instruments", "users", "marketdata", "orders");
  private static final String SNAPSHOT_EXISTS =
      "select exists (select 1 from information_schema.schemata where schema_name = '"
          + SNAPSHOT_SCHEMA
          + "')";
  private static final String TRUNCATE_ALL = "truncate " + String.join(", ", TABLES);

  private OriginalData() {}

  public static void restore(JdbcClient jdbc) {
    if (!snapshotExists(jdbc)) {
      createSnapshot(jdbc);
    }
    jdbc.sql(TRUNCATE_ALL).update();
    for (String table : TABLES) {
      String original = SNAPSHOT_SCHEMA + "." + table;
      jdbc.sql("insert into " + table + " select * from " + original).update();
      jdbc.sql(
              "select setval(pg_get_serial_sequence('"
                  + table
                  + "', 'id'), (select max(id) from "
                  + original
                  + "))")
          .query()
          .singleRow();
    }
  }

  private static boolean snapshotExists(JdbcClient jdbc) {
    return jdbc.sql(SNAPSHOT_EXISTS).query(Boolean.class).single();
  }

  private static void createSnapshot(JdbcClient jdbc) {
    jdbc.sql("create schema " + SNAPSHOT_SCHEMA).update();
    for (String table : TABLES) {
      jdbc.sql("create table " + SNAPSHOT_SCHEMA + "." + table + " as table " + table).update();
    }
  }
}
