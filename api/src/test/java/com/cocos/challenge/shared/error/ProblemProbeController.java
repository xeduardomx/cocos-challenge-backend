package com.cocos.challenge.shared.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exist only to make the handler produce each kind of error. */
@RestController
public class ProblemProbeController {

  // Any key without arguments will do: the test only needs to see the text come out of the bundle
  static final String VIOLATION_KEY = "{request.malformed}";

  record ProbeRequest(
      @Positive(message = VIOLATION_KEY) Integer quantity,
      @Positive(message = VIOLATION_KEY) Integer price) {}

  static final class ProbeException extends ApiException {
    ProbeException() {
      super(ProblemType.ORDER_NOT_CANCELLABLE, "request.unknown-field", "extra");
    }

    @Override
    public Map<String, Object> properties() {
      return Map.of("currentStatus", "FILLED");
    }

    @Override
    public Optional<Duration> retryAfter() {
      return Optional.of(Duration.ofSeconds(1));
    }
  }

  @PostMapping("/probe")
  void body(@Valid @RequestBody ProbeRequest request) {}

  @GetMapping("/probe/{id}")
  void pathValue(@PathVariable long id) {}

  @GetMapping("/probe/api-error")
  void apiError() {
    throw new ProbeException();
  }

  @GetMapping("/probe/invalid-request")
  void invalidRequest() {
    throw new InvalidRequestException("limit", "request.invalid-value", "limit");
  }

  @GetMapping("/probe/unexpected")
  void unexpected() {
    throw new IllegalStateException("secret");
  }

  /** What Tomcat throws when it cannot read the parameters: MockMvc never parses them. */
  @GetMapping("/probe/unreadable-parameters")
  void unreadableParameters() {
    throw new InvalidParameterException("unreadable parameters");
  }
}
