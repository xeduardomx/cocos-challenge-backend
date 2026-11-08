package com.cocos.challenge.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountConfiguration {

  @Bean
  AccountCalculator accountCalculator() {
    return new AccountCalculator();
  }
}
