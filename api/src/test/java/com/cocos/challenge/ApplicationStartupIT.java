package com.cocos.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ApplicationStartupIT {

  private static final int ORDERS_IN_COCOS_DATABASE = 11;

  @Autowired private Flyway flyway;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void startsAgainstTheCocosDatabase() {
    Integer orders = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);

    assertThat(flyway).isNotNull();
    assertThat(orders).isEqualTo(ORDERS_IN_COCOS_DATABASE);
  }
}
