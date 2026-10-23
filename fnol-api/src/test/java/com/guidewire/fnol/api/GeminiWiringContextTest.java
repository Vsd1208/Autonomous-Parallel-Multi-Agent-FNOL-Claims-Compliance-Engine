package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.GeminiVisionConfig;
import com.guidewire.fnol.agents.GeminiVisionProvider;
import com.guidewire.fnol.agents.VisionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D10: with Gemini enabled, the provider gets its own RestTemplate, and the API's ObjectMapper is intact. */
@SpringBootTest(properties = {"gemini.vision.enabled=true", "gemini.api-key="})
class GeminiWiringContextTest {

    @Autowired private VisionProvider visionProvider;
    @Autowired @Qualifier(GeminiVisionConfig.GEMINI_REST_TEMPLATE) private RestTemplate geminiRestTemplate;
    @Autowired private RestTemplate defaultRestTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("GeminiVisionProvider is injected with the Gemini RestTemplate, not the Guidewire one")
    void geminiProviderGetsGeminiRestTemplate() {
        assertThat(visionProvider).isInstanceOf(GeminiVisionProvider.class);
        Object injected = ReflectionTestUtils.getField(visionProvider, "restTemplate");
        assertThat(injected).isSameAs(geminiRestTemplate);
        assertThat(injected).isNotSameAs(defaultRestTemplate);
    }

    @Test
    @DisplayName("The auto-configured ObjectMapper still serialises LocalDate as ISO text")
    void objectMapperSupportsJavaTime() throws Exception {
        assertThat(objectMapper.writeValueAsString(Map.of("d", LocalDate.of(2026, 9, 27))))
                .isEqualTo("{\"d\":\"2026-09-27\"}");
    }
}
