package com.cocos.challenge.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class OriginalDataIT {

  private static final int ORDER_TO_CANCEL = 7;
  private static final int NEXT_ORDER_ID = 12;

  @Autowired private JdbcClient jdbc;

  @Test
  void restoresTheOriginalDataAndTheIdCounters() {
    jdbc.sql("update orders set status = 'CANCELLED' where id = ?").param(ORDER_TO_CANCEL).update();
    jdbc.sql("insert into users (email, accountnumber) values ('t@test.local', '30001')").update();
    jdbc.sql(
            "insert into orders (instrumentid, userid, size, price, type, side, status)"
                + " values (47, 1, 10, 925.85, 'MARKET', 'BUY', 'FILLED')")
        .update();

    OriginalData.restore(jdbc);

    assertThat(
            jdbc.sql("select status from orders where id = ?")
                .param(ORDER_TO_CANCEL)
                .query(String.class)
                .single())
        .isEqualTo("NEW");
    assertThat(count("users")).isEqualTo(4);
    assertThat(count("orders")).isEqualTo(11);
    assertThat(jdbc.sql("select nextval('orders_id_seq')").query(Integer.class).single())
        .isEqualTo(NEXT_ORDER_ID);
  }

  private int count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
  }
}
