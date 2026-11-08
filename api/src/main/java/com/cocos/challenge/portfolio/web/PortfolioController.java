package com.cocos.challenge.portfolio.web;

import com.cocos.challenge.portfolio.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

  private final PortfolioService service;

  public PortfolioController(PortfolioService service) {
    this.service = service;
  }

  @GetMapping("/users/{userId}/portfolio")
  public PortfolioResponse portfolio(@PathVariable long userId) {
    return PortfolioResponse.from(userId, service.portfolioOf(userId));
  }
}
