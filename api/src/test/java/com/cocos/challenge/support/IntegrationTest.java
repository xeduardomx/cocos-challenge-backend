package com.cocos.challenge.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Import;

/** Marks a test that starts the whole application against the shared test Postgres. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "spring.flyway.enabled=true")
@ImportTestcontainers(PostgresContainer.class)
@Import({TestData.class, Concurrency.class})
@ExtendWith(OriginalDataExtension.class)
public @interface IntegrationTest {}
