package com.cocos.challenge.shared.display;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DisplayConfiguration {

  @Bean
  DisplayFormatter displayFormatter(DisplayProperties properties) {
    return new DisplayFormatter(properties);
  }
}
