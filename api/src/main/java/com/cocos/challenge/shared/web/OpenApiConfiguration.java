package com.cocos.challenge.shared.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The title, description and version of the published OpenAPI specification. */
@Configuration
public class OpenApiConfiguration {

  static final String TITLE = "Cocos Capital: API de portfolio y órdenes";
  static final String DESCRIPTION =
      """
      Consulta el portfolio de un usuario, busca activos y envía, cancela y lista órdenes. \
      Los errores usan Problem Details (RFC 9457). Las fechas salen en hora de Buenos Aires.\
      """;
  static final String VERSION = "1.0.0";

  private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
  private static final String PROBLEM_SCHEMA_NAME = "Problem";
  private static final String SCHEMA_REFERENCE_PREFIX = "#/components/schemas/";
  private static final char SUCCESS_CODE_FIRST_DIGIT = '2';

  @Bean
  OpenAPI openApi() {
    return new OpenAPI().info(new Info().title(TITLE).description(DESCRIPTION).version(VERSION));
  }

  /** Every error response (any code outside 2xx) is documented with the shape of a problem. */
  @Bean
  OpenApiCustomizer problemResponses() {
    return openApi -> {
      ModelConverters.getInstance()
          .readAll(ProblemBody.class)
          .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
      Schema<?> problem = new Schema<>().$ref(SCHEMA_REFERENCE_PREFIX + PROBLEM_SCHEMA_NAME);
      openApi
          .getPaths()
          .values()
          .forEach(path ->
              path.readOperations()
                  .forEach(operation ->
                      operation
                          .getResponses()
                          .forEach((code, response) -> {
                            if (code.charAt(0) != SUCCESS_CODE_FIRST_DIGIT) {
                              documentAsProblem(response, problem);
                            }
                          })));
    };
  }

  private static void documentAsProblem(ApiResponse response, Schema<?> problem) {
    response.setContent(
        new Content().addMediaType(PROBLEM_MEDIA_TYPE, new MediaType().schema(problem)));
  }
}
