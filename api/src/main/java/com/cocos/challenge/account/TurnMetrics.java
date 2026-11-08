package com.cocos.challenge.account;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** How long a submission waits for the turn of its user (RFC 8.2). */
@Component
public class TurnMetrics {

  private static final String TURN_WAIT = "orders.turn.wait";

  private final Timer turnWait;

  public TurnMetrics(MeterRegistry registry) {
    this.turnWait = registry.timer(TURN_WAIT);
  }

  /** Measures the wait, also when it runs out: that is the wait a dashboard must not miss. */
  public <T> T time(Supplier<T> wait) {
    return turnWait.record(wait);
  }
}
