package com.cocos.challenge.shared.display;

import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** How the API shows dates and money: the zone and the language of the texts. */
@Validated
@ConfigurationProperties("cocos.display")
public record DisplayProperties(@NotNull ZoneId timeZone, @NotNull Locale locale) {}
