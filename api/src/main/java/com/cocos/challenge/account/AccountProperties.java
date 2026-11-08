package com.cocos.challenge.account;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** How long an order waits for the turn of its user before the API asks to retry (RFC 5.2). */
@Validated
@ConfigurationProperties("cocos.account")
public record AccountProperties(@NotNull Duration turnTimeout) {}
