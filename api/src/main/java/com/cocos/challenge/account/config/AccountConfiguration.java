package com.cocos.challenge.account.config;

import com.cocos.challenge.account.domain.AccountCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountConfiguration {

  @Bean
  AccountCalculator accountCalculator() {
    return new AccountCalculator();
  }
}
