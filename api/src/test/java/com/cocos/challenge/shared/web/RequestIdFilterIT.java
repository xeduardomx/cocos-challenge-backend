package com.cocos.challenge.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.cocos.challenge.shared.display.DisplayProperties;
import com.cocos.challenge.shared.error.ProblemProbeController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@EnableConfigurationProperties(DisplayProperties.class)
@WebMvcTest(ProblemProbeController.class)
class RequestIdFilterIT {

  @Autowired private MockMvc mockMvc;

  private String responseRequestId(String sent) throws Exception {
    return idOf(get("/nope").header(RequestIds.HEADER, sent));
  }

  private String idOf(MockHttpServletRequestBuilder request) throws Exception {
    return String.valueOf(
        mockMvc.perform(request).andReturn().getResponse().getHeader(RequestIds.HEADER));
  }

  @Test
  void echoesAValidRequestId() throws Exception {
    assertThat(responseRequestId("abc-123")).isEqualTo("abc-123");
  }

  @Test
  void generatesAnIdWhenMissing() throws Exception {
    String generated = idOf(get("/nope"));

    assertThat(UUID.fromString(generated)).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"line\nbreak", "has space", "ñandú", ""})
  void replacesAnUnsafeRequestId(String unsafe) throws Exception {
    String replaced = responseRequestId(unsafe);

    assertThat(replaced).isNotEqualTo(unsafe);
    assertThat(UUID.fromString(replaced)).isNotNull();
  }

  @Test
  void replacesARequestIdThatIsTooLong() throws Exception {
    String tooLong = "a".repeat(RequestIds.MAX_LENGTH + 1);

    assertThat(responseRequestId(tooLong)).isNotEqualTo(tooLong);
  }

  @Test
  void acceptsARequestIdOfTheMaximumLength() throws Exception {
    String longest = "a".repeat(RequestIds.MAX_LENGTH);

    assertThat(responseRequestId(longest)).isEqualTo(longest);
  }

  @Test
  void clearsTheLogContextAfterTheRequest() throws Exception {
    responseRequestId("abc-123");

    assertThat(RequestIds.current()).isNull();
  }
}
