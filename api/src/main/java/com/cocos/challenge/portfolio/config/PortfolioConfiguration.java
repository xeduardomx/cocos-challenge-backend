package com.cocos.challenge.portfolio.config;

import com.cocos.challenge.portfolio.domain.PortfolioValuator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PortfolioConfiguration {

  @Bean
  PortfolioValuator portfolioValuator() {
    return new PortfolioValuator();
  }
}
