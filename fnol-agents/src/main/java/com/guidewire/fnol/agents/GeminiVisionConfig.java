package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring configuration for Gemini Vision integration.
 * Beans are only registered when {@code gemini.vision.enabled=true},
 * keeping the test context lightweight.
 */
@Configuration
@ConditionalOnProperty(name = "gemini.vision.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiVisionConfig {

    @Bean
    public RestTemplate geminiRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper geminiObjectMapper() {
        return new ObjectMapper();
    }
}
