package com.cocos.challenge.instruments.domain;

import org.jspecify.annotations.Nullable;

/** An instrument found by the search, with its latest price when it has one. */
public record SearchResult(Instrument instrument, @Nullable MarketPrice price) {}
