package com.cocos.challenge.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TurnMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final TurnMetrics metrics = new TurnMetrics(registry);

  private long waits() {
    return registry.get("orders.turn.wait").timer().count();
  }

  @Test
  void theTimerExistsBeforeTheFirstWait() {
    assertThat(waits()).isZero();
  }

  @Test
  void answersWhatTheWaitAnsweredAndCountsIt() {
    assertThat(metrics.time(() -> true)).isTrue();

    assertThat(waits()).isEqualTo(1);
  }

  @Test
  void aWaitThatRanOutCountsToo() {
    Supplier<Boolean> ranOut =
        () -> {
          throw new IllegalStateException("The wait ran out");
        };

    assertThatThrownBy(() -> metrics.time(ranOut)).isInstanceOf(IllegalStateException.class);

    assertThat(waits()).isEqualTo(1);
  }
}
