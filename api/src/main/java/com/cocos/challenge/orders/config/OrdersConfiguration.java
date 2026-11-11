package com.cocos.challenge.orders.config;

import com.cocos.challenge.orders.domain.evaluation.EnoughCashRule;
import com.cocos.challenge.orders.domain.evaluation.EnoughSharesRule;
import com.cocos.challenge.orders.domain.evaluation.OrderEvaluator;
import com.cocos.challenge.orders.domain.evaluation.QuotedInstrumentRule;
import com.cocos.challenge.orders.domain.submission.QuantityResolver;
import com.cocos.challenge.shared.display.DisplayFormatter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrdersConfiguration {

  @Bean
  QuantityResolver quantityResolver(DisplayFormatter displayFormatter) {
    return new QuantityResolver(displayFormatter);
  }

  @Bean
  QuotedInstrumentRule quotedInstrumentRule() {
    return new QuotedInstrumentRule();
  }

  @Bean
  EnoughCashRule enoughCashRule() {
    return new EnoughCashRule();
  }

  @Bean
  EnoughSharesRule enoughSharesRule() {
    return new EnoughSharesRule();
  }

  /** The first rule that rejects wins, so the order of the list matters. */
  @Bean
  OrderEvaluator orderEvaluator(
      QuotedInstrumentRule quotedInstrumentRule,
      EnoughCashRule enoughCashRule,
      EnoughSharesRule enoughSharesRule) {
    return new OrderEvaluator(List.of(quotedInstrumentRule, enoughCashRule, enoughSharesRule));
  }
}
