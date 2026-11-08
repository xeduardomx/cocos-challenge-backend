package com.cocos.challenge.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cocos.challenge.shared.display.DisplayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
@EnableConfigurationProperties(DisplayProperties.class)
@WebMvcTest(ProblemProbeController.class)
@ImportAutoConfiguration(MessageSourceAutoConfiguration.class)
class ApiExceptionHandlerIT {

  private static final String VALIDATION_ERROR = "/problems/validation-error";
  private static final String MALFORMED_TEXT = "El cuerpo del pedido no es un JSON válido.";

  @Autowired private MockMvc mockMvc;

  private ResultActions postProbe(String json) throws Exception {
    return mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void rejectsAnUnknownField() throws Exception {
    postProbe("{\"prise\": 1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.errors[0].field").value("prise"))
        .andExpect(jsonPath("$.detail").value("El campo prise no existe en este pedido."));
  }

  @Test
  void rejectsADecimalForAnInteger() throws Exception {
    postProbe("{\"quantity\": 2.5}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.errors[0].field").value("quantity"));
  }

  @Test
  void rejectsMalformedJson() throws Exception {
    postProbe("{\"quantity\": ")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.title").value("El pedido no es válido"))
        .andExpect(jsonPath("$.detail").value(MALFORMED_TEXT))
        .andExpect(jsonPath("$.instance").value("/probe"));
  }

  @Test
  void reportsEachBeanValidationViolation() throws Exception {
    postProbe("{\"quantity\": 0, \"price\": 5}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.errors[0].field").value("quantity"))
        .andExpect(jsonPath("$.errors[0].message").value(MALFORMED_TEXT));
  }

  @Test
  void putsTheOnlyViolationInTheDetail() throws Exception {
    postProbe("{\"quantity\": 0, \"price\": 5}")
        .andExpect(jsonPath("$.detail").value(MALFORMED_TEXT));
    postProbe("{\"quantity\": 0, \"price\": 0}")
        .andExpect(jsonPath("$.errors.length()").value(2))
        .andExpect(
            jsonPath("$.detail")
                .value("El pedido tiene 2 errores: el detalle de cada uno está en errors."));
  }

  @Test
  void reportsABodyThatIsNotAnObjectUnderTheBodyField() throws Exception {
    postProbe("[]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
  }

  @Test
  void publishesAnInvalidRequestExceptionInErrors() throws Exception {
    mockMvc
        .perform(get("/probe/invalid-request"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.errors[0].field").value("limit"))
        .andExpect(jsonPath("$.errors[0].message").value("El valor de limit no es válido."))
        .andExpect(header().doesNotExist("Retry-After"));
  }

  @Test
  void rejectsAPathValueOfTheWrongType() throws Exception {
    mockMvc
        .perform(get("/probe/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(VALIDATION_ERROR))
        .andExpect(jsonPath("$.errors[0].field").value("id"));
  }

  @Test
  void mapsAnApiExceptionToItsProblem() throws Exception {
    mockMvc
        .perform(get("/probe/api-error"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("/problems/order-not-cancellable"))
        .andExpect(jsonPath("$.title").value("La orden no se puede cancelar"))
        .andExpect(jsonPath("$.detail").value("El campo extra no existe en este pedido."))
        .andExpect(jsonPath("$.currentStatus").value("FILLED"))
        .andExpect(header().string("Retry-After", "1"));
  }

  @Test
  void hidesTheInternalsOfAnUnexpectedError(CapturedOutput output) throws Exception {
    mockMvc
        .perform(get("/probe/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("/problems/internal-error"))
        .andExpect(jsonPath("$.detail").value("Ocurrió un error inesperado."))
        .andExpect(jsonPath("$.requestId").isNotEmpty())
        .andExpect(content().string(not(containsString("secret"))));

    assertThat(output).contains("Unexpected error").contains("IllegalStateException: secret");
  }

  @Test
  void leavesSpringErrorsAsAboutBlank() throws Exception {
    mockMvc
        .perform(get("/nope"))
        .andExpect(status().isNotFound())
        // Spring omits a type of about:blank from the JSON: its absence means about:blank (RFC
        // 9457)
        .andExpect(jsonPath("$.type").doesNotExist())
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
  }
}
