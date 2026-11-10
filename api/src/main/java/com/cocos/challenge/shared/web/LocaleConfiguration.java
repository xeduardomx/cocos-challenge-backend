package com.cocos.challenge.shared.web;

import com.cocos.challenge.shared.display.DisplayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

/**
 * The API speaks the display language only: the {@code Accept-Language} of the request does not
 * change it. Spring MVC hands this locale to Bean Validation, so its texts follow it too.
 */
@Configuration
public class LocaleConfiguration {

  /** Under the name Spring MVC looks for, which also makes Spring Boot leave out its own. */
  @Bean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
  LocaleResolver localeResolver(DisplayProperties display) {
    return new FixedLocaleResolver(display.locale());
  }
}
