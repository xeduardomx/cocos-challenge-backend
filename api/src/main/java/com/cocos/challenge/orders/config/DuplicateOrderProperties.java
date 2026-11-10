package com.cocos.challenge.orders.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * For how long an accepted order makes an equal one, sent with another client order id, a possible
 * duplicate (RFC 5.5). Zero turns the check off: no order is that recent.
 */
@Validated
@ConfigurationProperties("cocos.orders.duplicates")
public record DuplicateOrderProperties(@NotNull @DurationMin(millis = 0) Duration window) {}
