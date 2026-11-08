package com.cocos.challenge.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.shared.display.DisplayProperties;
import java.beans.PropertyChangeEvent;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

/** The cases of the handler that no HTTP request can reach with a plain controller. */
class ApiExceptionHandlerTest {

  private final StaticMessageSource messages = new StaticMessageSource();
  private final ApiExceptionHandler handler =
      new ApiExceptionHandler(messages, new DisplayProperties(ZoneOffset.UTC, Locale.ROOT));
  private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

  ApiExceptionHandlerTest() {
    messages.addMessage("problem.validation-error.title", Locale.ROOT, "title");
    messages.addMessage("request.invalid-value", Locale.ROOT, "invalid {0}");
  }

  private static ProblemDetail problemOf(@Nullable ResponseEntity<Object> response) {
    ResponseEntity<Object> present = Objects.requireNonNull(response);
    assertThat(present.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    return (ProblemDetail) Objects.requireNonNull(present.getBody());
  }

  private static MethodParameter anyMethodParameter() throws NoSuchMethodException {
    return new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
  }

  @Test
  void namesTheFieldOfATypeMismatchThatHasOne() {
    var mismatch =
        new TypeMismatchException(new PropertyChangeEvent(this, "limit", null, "x"), Long.class);

    var problem =
        problemOf(
            handler.handleTypeMismatch(
                mismatch, new HttpHeaders(), HttpStatus.BAD_REQUEST, request));

    assertThat(problem.getDetail()).isEqualTo("invalid limit");
  }

  @Test
  void usesAGenericFieldForATypeMismatchWithoutOne() {
    var mismatch = new TypeMismatchException("x", Long.class);

    var problem =
        problemOf(
            handler.handleTypeMismatch(
                mismatch, new HttpHeaders(), HttpStatus.BAD_REQUEST, request));

    assertThat(problem.getDetail()).isEqualTo("invalid value");
  }

  @Test
  void reportsAnObjectLevelViolationUnderTheObjectName() throws NoSuchMethodException {
    var binding = new BeanPropertyBindingResult(new Object(), "order");
    binding.reject("code", "whole order is wrong");
    var invalid = new MethodArgumentNotValidException(anyMethodParameter(), binding);

    var problem =
        problemOf(
            handler.handleMethodArgumentNotValid(
                invalid, new HttpHeaders(), HttpStatus.BAD_REQUEST, request));

    assertThat(problem.getProperties())
        .containsEntry("errors", List.of(new FieldViolation("order", "whole order is wrong")));
  }
}
