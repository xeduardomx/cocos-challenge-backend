package com.cocos.challenge.support;

import com.cocos.challenge.shared.web.RequestIds;
import java.util.List;
import java.util.Set;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the log lines of a test as JSON (RFC 8.1). A stack trace travels inside the JSON of its
 * line, with its line breaks escaped: it can only be seen by reading the field.
 */
public final class LogLines {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String JSON_LINE_START = "{";
  private static final String LEVEL_POINTER = "/log/level";
  private static final String MESSAGE_POINTER = "/message";
  private static final String HTTP_STATUS_FIELD = "httpStatus";
  private static final String PROBLEM_TYPE_FIELD = "problemType";
  private static final String STACK_TRACE_POINTER = "/error/stack_trace";
  private static final Set<String> LEVELS_OF_AN_ERROR = Set.of("WARN", "ERROR");

  private LogLines() {}

  /** Every line of one request, found by its id, whatever its level. */
  public static List<JsonNode> ofRequest(CapturedOutput output, String requestId) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.startsWith(JSON_LINE_START))
        .map(MAPPER::readTree)
        .filter(line -> requestId.equals(line.path(RequestIds.MDC_KEY).asString()))
        .toList();
  }

  /** The lines of one request at WARN or ERROR, from any logger of the API or of Spring. */
  public static List<JsonNode> errorsOfRequest(CapturedOutput output, String requestId) {
    return ofRequest(output, requestId).stream()
        .filter(line -> LEVELS_OF_AN_ERROR.contains(level(line)))
        .toList();
  }

  public static String level(JsonNode line) {
    return line.at(LEVEL_POINTER).asString();
  }

  public static String message(JsonNode line) {
    return line.at(MESSAGE_POINTER).asString();
  }

  public static int httpStatus(JsonNode line) {
    return line.path(HTTP_STATUS_FIELD).asInt();
  }

  public static String problemType(JsonNode line) {
    return line.path(PROBLEM_TYPE_FIELD).asString();
  }

  public static boolean hasStackTrace(JsonNode line) {
    return !line.at(STACK_TRACE_POINTER).isMissingNode();
  }

  public static String stackTrace(JsonNode line) {
    return line.at(STACK_TRACE_POINTER).asString();
  }
}
