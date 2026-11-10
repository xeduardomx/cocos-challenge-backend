package com.cocos.challenge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Only health and prometheus are published: the others can show configuration or memory. */
@IntegrationTest
class ActuatorExposureIT {

  @LocalServerPort private int port;

  private int statusOf(String path) {
    return new ApiClient(port).getText(path).status();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health", "/actuator/prometheus"})
  void publishesHealthAndTheMetrics(String path) {
    assertThat(statusOf(path)).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/env", "/actuator/heapdump"})
  void hidesWhatCanShowSecrets(String path) {
    assertThat(statusOf(path)).isEqualTo(404);
  }
}
