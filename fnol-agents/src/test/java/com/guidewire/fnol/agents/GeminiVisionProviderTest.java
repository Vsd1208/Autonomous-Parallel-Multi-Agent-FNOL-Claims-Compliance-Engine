package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GeminiVisionProvider.
 * Uses a mocked RestTemplate — no real Gemini API calls are made.
 */
class GeminiVisionProviderTest {

    private RestTemplate restTemplate;
    private GeminiVisionProvider provider;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        provider = new GeminiVisionProvider(restTemplate, new ObjectMapper());
        // Inject test API key and model via reflection (simulates @Value injection)
        ReflectionTestUtils.setField(provider, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(provider, "model", "gemini-1.5-flash");
    }

    private FNOLPayload payloadWithUri(String uri) {
        return new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.of(2026, 8, 16),
                "CA",
                "Front bumper impact with barrier",
                "Avery Johnson",
                "01/02/1980",
                "123-45-6789",
                List.of(uri),
                null
        );
    }

    private String geminiResponse(String damageType, String severity, int estimate, double confidence) {
        return """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "{ \\"damageType\\": \\"%s\\", \\"severity\\": \\"%s\\", \\"estimatedDamage\\": %d, \\"confidence\\": %.2f, \\"photoQuality\\": \\"CLEAR\\", \\"damageTypeProbabilities\\": { \\"%s\\": %.2f, \\"UNKNOWN\\": 0.05 } }"
                      }]
                    }
                  }]
                }
                """.formatted(damageType, severity, estimate, confidence, damageType, confidence);
    }

    @Test
    @DisplayName("Parses Gemini response into correct DamageAssessment for base64 image")
    void assess_withBase64Image_parsesResponseCorrectly() {
        String base64Uri = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("FRONT_BUMPER", "MODERATE", 4200, 0.91)));

        DamageAssessment result = provider.assess(payloadWithUri(base64Uri));

        assertThat(result.damageType()).isEqualTo("FRONT_BUMPER");
        assertThat(result.severity()).isEqualTo("MODERATE");
        assertThat(result.estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(result.confidence()).isEqualByComparingTo("0.91");
        assertThat(result.photoQuality()).isEqualTo("CLEAR");
        assertThat(result.damageTypeProbabilities()).containsKey("FRONT_BUMPER");
        assertThat(result.damageTypeProbabilities().get("FRONT_BUMPER")).isEqualByComparingTo("0.91");
    }

    @Test
    @DisplayName("Parses Gemini response into correct DamageAssessment for public image URL")
    void assess_withPublicUrl_parsesResponseCorrectly() {
        String imageUrl = "https://example.com/car-damage.jpg";
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("REAR_BUMPER", "SEVERE", 12500, 0.88)));

        DamageAssessment result = provider.assess(payloadWithUri(imageUrl));

        assertThat(result.damageType()).isEqualTo("REAR_BUMPER");
        assertThat(result.severity()).isEqualTo("SEVERE");
        assertThat(result.estimatedDamage()).isEqualByComparingTo("12500");
        assertThat(result.confidence()).isEqualByComparingTo("0.88");
        assertThat(result.photoQuality()).isEqualTo("CLEAR");
        assertThat(result.damageTypeProbabilities()).containsKey("REAR_BUMPER");
    }

    @Test
    @DisplayName("Strips markdown code fences from Gemini response if present")
    void assess_stripsMarkdownCodeFences() {
        String responseWithFences = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{
                        "text": "```json\\n{ \\"damageType\\": \\"WINDSHIELD\\", \\"severity\\": \\"MINOR\\", \\"estimatedDamage\\": 850, \\"confidence\\": 0.95 }\\n```"
                      }]
                    }
                  }]
                }
                """;
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseWithFences));

        DamageAssessment result = provider.assess(payloadWithUri("https://example.com/windshield.jpg"));

        assertThat(result.damageType()).isEqualTo("WINDSHIELD");
        assertThat(result.severity()).isEqualTo("MINOR");
        assertThat(result.estimatedDamage()).isEqualByComparingTo("850");
    }

    @Test
    @DisplayName("Falls back gracefully when Gemini API returns an error")
    void assess_geminiApiError_returnsFallbackAssessment() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        DamageAssessment result = provider.assess(payloadWithUri("https://example.com/photo.jpg"));

        // Fallback: UNKNOWN type, UNUSABLE/UNKNOWN photoQuality, 0.60 confidence (routes to HUMAN_REVIEW)
        assertThat(result.damageType()).isEqualTo("UNKNOWN");
        assertThat(result.confidence()).isEqualByComparingTo("0.60");
        assertThat(result.photoQuality()).isIn("UNKNOWN", "UNUSABLE");
    }

    @Test
    @DisplayName("Falls back gracefully when no evidenceUris are provided")
    void assess_noImageProvided_returnsFallbackAssessment() {
        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233", LocalDate.of(2026, 8, 16), "CA",
                "No photo available", null, null, null, List.of(), null
        );

        DamageAssessment result = provider.assess(payload);

        assertThat(result.damageType()).isEqualTo("UNKNOWN");
        assertThat(result.confidence()).isEqualByComparingTo("0.60");
        assertThat(result.photoQuality()).isEqualTo("UNUSABLE");  // no image = UNUSABLE
        // No REST call should have been made
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Sends prompt and image URI inside the Gemini request body")
    void assess_sendsCorrectRequestStructure() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("SIDE_PANEL", "MODERATE", 3100, 0.82)));

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        provider.assess(payloadWithUri("https://example.com/side.jpg"));
        verify(restTemplate).postForEntity(contains("gemini"), captor.capture(), eq(String.class));

        String requestBody = captor.getValue().getBody().toString();
        assertThat(requestBody).contains("damage assessor");   // prompt keyword
    }
}
