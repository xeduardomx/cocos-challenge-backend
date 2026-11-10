package com.cocos.challenge.portfolio.web;

import java.math.BigDecimal;

/** The pesos of a user: the balance, what pending orders reserve, and what is left. */
public record CashResponse(BigDecimal balance, BigDecimal reserved, BigDecimal available) {}
