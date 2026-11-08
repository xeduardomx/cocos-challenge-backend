package com.cocos.challenge.instruments.web;

import java.util.List;

/** The response of the instrument search. */
public record InstrumentSearchResponse(List<InstrumentSummary> instruments) {}
