package com.cocos.challenge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cocos.challenge.support.ApiClient;
import com.cocos.challenge.support.ApiClient.ApiResponse;
import com.cocos.challenge.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OpenApiIT {

  private static final String API_DOCS = "/v3/api-docs";
  private static final String SWAGGER_UI = "/swagger-ui.html";
  private static final String SWAGGER_UI_PAGE = "/swagger-ui/index.html";

  @LocalServerPort private int port;

  private ApiClient api;

  @BeforeEach
  void createClient() {
    api = new ApiClient(port);
  }

  @Test
  void publishesTheFiveOperations() {
    ApiResponse response = api.get(API_DOCS);

    assertThat(response.status()).isEqualTo(200);
    JsonNode paths = response.json().path("paths");
    assertThat(paths.path("/users/{userId}/portfolio").has("get")).isTrue();
    assertThat(paths.path("/instruments").has("get")).isTrue();
    assertThat(paths.path("/users/{userId}/orders").has("get")).isTrue();
    assertThat(paths.path("/users/{userId}/orders").has("post")).isTrue();
    assertThat(paths.path("/users/{userId}/orders/{orderId}/cancel").has("post")).isTrue();
  }

  @Test
  void documentsEveryErrorAsAProblem() {
    JsonNode responses =
        api.get(API_DOCS)
            .json()
            .path("paths")
            .path("/users/{userId}/orders/{orderId}/cancel")
            .path("post")
            .path("responses");

    assertThat(responses.path("409").path("content").has("application/problem+json")).isTrue();
    assertThat(responses.path("200").path("content").has("application/json")).isTrue();
  }

  @Test
  void sendsThePageToTryTheApiToItsRealAddress() {
    ApiResponse response = api.get(SWAGGER_UI);

    assertThat(response.status()).isEqualTo(302);
    assertThat(response.headers().getLocation()).hasPath(SWAGGER_UI_PAGE);
  }
}
