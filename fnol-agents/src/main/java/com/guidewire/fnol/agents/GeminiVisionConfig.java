package com.guidewire.fnol.agents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Spring configuration for Gemini Vision integration.
 * Beans are only registered when {@code gemini.vision.enabled=true}.
 *
 * <p>The Gemini client gets its own RestTemplate, injected by qualifier, because image
 * analysis is far slower than the 3 s read timeout used for Guidewire calls. The read
 * timeout stays below the orchestrator's 10 s deadline so the fallback assessment is
 * returned before the orchestrator gives up on Branch A.
 *
 * <p>No ObjectMapper bean is declared here: a bare ObjectMapper bean would replace Spring
 * Boot's auto-configured one and break LocalDate serialisation in the REST API.
 */
@Configuration
@ConditionalOnProperty(name = "gemini.vision.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiVisionConfig {

    public static final String GEMINI_REST_TEMPLATE = "geminiRestTemplate";

    @Bean(GEMINI_REST_TEMPLATE)
    public RestTemplate geminiRestTemplate(
            RestTemplateBuilder builder,
            @Value("${gemini.vision.read-timeout-seconds:8}") long readTimeoutSeconds) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .requestFactory(SimpleClientHttpRequestFactory::new)
                .build();
    }
}
