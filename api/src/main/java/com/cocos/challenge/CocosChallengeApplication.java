package com.cocos.challenge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CocosChallengeApplication {

  public static void main(String[] args) {
    SpringApplication.run(CocosChallengeApplication.class, args);
  }
}
