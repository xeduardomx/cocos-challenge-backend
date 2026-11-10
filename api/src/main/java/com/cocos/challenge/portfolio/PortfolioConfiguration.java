package com.cocos.challenge.portfolio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PortfolioConfiguration {

  @Bean
  PortfolioValuator portfolioValuator() {
    return new PortfolioValuator();
  }
}
