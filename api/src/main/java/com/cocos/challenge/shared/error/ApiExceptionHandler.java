package com.cocos.challenge.shared.error;

import com.cocos.challenge.shared.display.DisplayProperties;
import com.cocos.challenge.shared.web.RequestIds;
import java.util.List;
import java.util.Locale;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** Turns every error into a Problem Details response (RFC 7). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private static final String BODY_FIELD = "body";
  private static final String VALUE_FIELD = "value";
  private static final String ERRORS_PROPERTY = "errors";
  private static final String SEVERAL_ERRORS_KEY = "request.several-errors";
  private static final String MALFORMED_KEY = "request.malformed";
  private static final String UNKNOWN_FIELD_KEY = "request.unknown-field";
  private static final String INVALID_VALUE_KEY = "request.invalid-value";
  private static final String BAD_ENCODING_KEY = "request.bad-encoding";
  private static final String UNEXPECTED_KEY = "error.unexpected";

  private final MessageSource messages;
  private final Locale locale;

  public ApiExceptionHandler(MessageSource messages, DisplayProperties display) {
    this.messages = messages;
    this.locale = display.locale();
  }

  @ExceptionHandler(ApiException.class)
  @Nullable ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
    ProblemType type = ex.problemType();
    String detail = text(ex.detailKey(), ex.detailArgs());
    ProblemDetail problem = problem(type, detail);
    ex.properties().forEach(problem::setProperty);
    if (ex instanceof InvalidRequestException invalid) {
      problem.setProperty(ERRORS_PROPERTY, List.of(new FieldViolation(invalid.field(), detail)));
    }
    HttpHeaders headers = new HttpHeaders();
    ex.retryAfter()
        .ifPresent(wait -> headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(wait.toSeconds())));
    return handleExceptionInternal(ex, problem, headers, type.status(), request);
  }

  /**
   * Tomcat decodes the parameters the first time one is read, and fails if they are not UTF-8. It
   * does not say which one, so the problem names no field.
   */
  @ExceptionHandler(InvalidParameterException.class)
  @Nullable ResponseEntity<Object> handleBadEncoding(
      InvalidParameterException ex, WebRequest request) {
    ProblemDetail problem = problem(ProblemType.VALIDATION_ERROR, text(BAD_ENCODING_KEY));
    return handleExceptionInternal(
        ex, problem, new HttpHeaders(), ProblemType.VALIDATION_ERROR.status(), request);
  }

  @ExceptionHandler(Exception.class)
  @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
    LOG.error("Unexpected error", ex);
    ProblemDetail problem = problem(ProblemType.INTERNAL_ERROR, text(UNEXPECTED_KEY));
    return handleExceptionInternal(
        ex, problem, new HttpHeaders(), ProblemType.INTERNAL_ERROR.status(), request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldViolation> violations =
        ex.getBindingResult().getAllErrors().stream()
            .map(ApiExceptionHandler::toViolation)
            .toList();
    return validationProblem(ex, violations, headers, request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return validationProblem(ex, List.of(bodyViolation(ex.getCause())), headers, request);
  }

  @Override
  protected @Nullable ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String field =
        ex instanceof MethodArgumentTypeMismatchException argument
            ? argument.getName()
            : propertyNameOrDefault(ex);
    FieldViolation violation = new FieldViolation(field, text(INVALID_VALUE_KEY, field));
    return validationProblem(ex, List.of(violation), headers, request);
  }

  private @Nullable ResponseEntity<Object> validationProblem(
      Exception ex, List<FieldViolation> violations, HttpHeaders headers, WebRequest request) {
    String detail =
        violations.size() == 1
            ? violations.getFirst().message()
            : text(SEVERAL_ERRORS_KEY, String.valueOf(violations.size()));
    ProblemDetail problem = problem(ProblemType.VALIDATION_ERROR, detail);
    problem.setProperty(ERRORS_PROPERTY, violations);
    return handleExceptionInternal(
        ex, problem, headers, ProblemType.VALIDATION_ERROR.status(), request);
  }

  /**
   * Jackson gives the path of the value it could not read, such as a number that does not fit its
   * field: the problem names that field. Only an error without a path is a malformed body.
   */
  private FieldViolation bodyViolation(@Nullable Throwable cause) {
    if (!(cause instanceof JacksonException jackson) || jackson.getPath().isEmpty()) {
      return new FieldViolation(BODY_FIELD, text(MALFORMED_KEY));
    }
    String field = lastPropertyName(jackson);
    String key =
        jackson instanceof UnrecognizedPropertyException ? UNKNOWN_FIELD_KEY : INVALID_VALUE_KEY;
    return new FieldViolation(field, text(key, field));
  }

  private static String lastPropertyName(JacksonException exception) {
    List<JacksonException.Reference> path = exception.getPath();
    for (int i = path.size() - 1; i >= 0; i--) {
      String name = path.get(i).getPropertyName();
      if (name != null) {
        return name;
      }
    }
    return BODY_FIELD;
  }

  private static String propertyNameOrDefault(TypeMismatchException ex) {
    String name = ex.getPropertyName();
    return name != null ? name : VALUE_FIELD;
  }

  private static FieldViolation toViolation(ObjectError error) {
    String field =
        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
    return new FieldViolation(field, String.valueOf(error.getDefaultMessage()));
  }

  private ProblemDetail problem(ProblemType type, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
    problem.setType(type.uri());
    problem.setTitle(text(type.titleKey()));
    return problem;
  }

  private String text(String key, String... args) {
    return messages.getMessage(key, args, locale);
  }

  /**
   * Every response goes through here, including the ones Spring builds, so the id is added once.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    String requestId = RequestIds.current();
    if (body instanceof ProblemDetail problem && requestId != null) {
      problem.setProperty(RequestIds.MDC_KEY, requestId);
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }
}
