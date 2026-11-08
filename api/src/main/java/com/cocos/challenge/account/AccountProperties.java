package com.cocos.challenge.account;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long an order waits for the turn of its user before the API asks to retry (RFC 5.2). At least
 * one millisecond: Postgres takes a {@code lock_timeout} of zero as no limit.
 */
@Validated
@ConfigurationProperties("cocos.account")
public record AccountProperties(@NotNull @DurationMin(millis = 1) Duration turnTimeout) {}
