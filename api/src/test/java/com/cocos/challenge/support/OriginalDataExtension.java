package com.cocos.challenge.support;

import javax.sql.DataSource;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Puts the database back to the original data before every test. */
public class OriginalDataExtension implements BeforeEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) {
    DataSource dataSource =
        SpringExtension.getApplicationContext(context).getBean(DataSource.class);
    OriginalData.restore(JdbcClient.create(dataSource));
  }
}
