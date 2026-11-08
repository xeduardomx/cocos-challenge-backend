package com.cocos.challenge.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The single Postgres of the whole test run. The container is a static field, so every Spring
 * context of the run shares it, however many contexts the tests create.
 */
public interface PostgresContainer {

  String IMAGE = "postgres:18";
  String DATABASE_SCRIPT = "database.sql";
  String INIT_SCRIPT_PATH = "/docker-entrypoint-initdb.d/01-database.sql";

  @ServiceConnection
  PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withCopyFileToContainer(
              MountableFile.forClasspathResource(DATABASE_SCRIPT), INIT_SCRIPT_PATH);
}
