package com.cocos.challenge.instruments.search;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** The limits of the instrument search: how many results and how long the text can be. */
@Validated
@ConfigurationProperties("cocos.search")
public record SearchProperties(@Positive int maxResults, @Positive int maxQueryLength) {}
