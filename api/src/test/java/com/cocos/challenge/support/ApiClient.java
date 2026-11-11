package com.cocos.challenge.support;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the running API over HTTP. It never throws on a 4xx or a 5xx: the tests assert on the
 * status. Numbers are read as {@link java.math.BigDecimal}.
 */
public class ApiClient {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private final String baseUrl;
  private final RestClient client;

  public ApiClient(int port) {
    this(port, new HttpHeaders());
  }

  /** Sends these headers in every request. */
  public ApiClient(int port, HttpHeaders defaultHeaders) {
    this.baseUrl = "http://localhost:" + port;
    this.client =
        RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeaders(headers -> headers.addAll(defaultHeaders))
            .build();
  }

  /** The path may have URI variables, which are encoded as values. */
  public ApiResponse get(String path, Object... uriVariables) {
    return client.get().uri(path, uriVariables).exchange(ApiClient::toResponse);
  }

  /** Sends the path exactly as written, already percent-encoded: for bytes that are not UTF-8. */
  public ApiResponse getEncoded(String encodedPath) {
    return client.get().uri(URI.create(baseUrl + encodedPath)).exchange(ApiClient::toResponse);
  }

  /** For a response that is not JSON, such as the scrape of Prometheus: only its raw body. */
  public ApiResponse getText(String path) {
    return client.get().uri(path).exchange(ApiClient::toTextResponse);
  }

  /** Sends the text as the body, untouched: for numbers and values Java cannot represent. */
  public ApiResponse post(String path, String rawJson) {
    return post(path, MediaType.APPLICATION_JSON, rawJson);
  }

  /** Sends the body with this content type: for a body the API does not accept. */
  public ApiResponse post(String path, MediaType contentType, String body) {
    return client
        .post()
        .uri(path)
        .contentType(contentType)
        .body(body)
        .exchange(ApiClient::toResponse);
  }

  private static ApiResponse toResponse(
      org.springframework.http.HttpRequest request,
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
      throws java.io.IOException {
    String body = bodyOf(response);
    JsonNode json = body.isBlank() ? MAPPER.missingNode() : MAPPER.readTree(body);
    return new ApiResponse(response.getStatusCode().value(), response.getHeaders(), body, json);
  }

  private static ApiResponse toTextResponse(
      org.springframework.http.HttpRequest request,
      RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
      throws java.io.IOException {
    return new ApiResponse(
        response.getStatusCode().value(),
        response.getHeaders(),
        bodyOf(response),
        MAPPER.missingNode());
  }

  private static String bodyOf(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
      throws java.io.IOException {
    return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
  }

  /** A response with its raw body and the body read as JSON. */
  public record ApiResponse(int status, HttpHeaders headers, String body, JsonNode json) {}
}
