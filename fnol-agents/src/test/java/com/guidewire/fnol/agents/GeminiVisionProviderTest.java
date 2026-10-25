package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GeminiVisionProvider.
 * Uses a mocked RestTemplate and a fake image downloader — no network calls are made.
 */
class GeminiVisionProviderTest {

    private RestTemplate restTemplate;
    private GeminiVisionProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        InetAddress publicIp = InetAddress.getByAddress("example.com", new byte[]{93, (byte) 184, (byte) 216, 34});
        EvidenceImageLoader loader = new EvidenceImageLoader(Set.of("example.com"),
                host -> new InetAddress[]{publicIp},
                uri -> new EvidenceImageLoader.Download(200, "image/jpeg", 4, new ByteArrayInputStream(new byte[]{1, 2, 3, 4})));
        provider = new GeminiVisionProvider(restTemplate, new ObjectMapper(), loader);
        ReflectionTestUtils.setField(provider, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(provider, "model", "gemini-2.5-flash");
    }

    private FNOLPayload payloadWithUri(String uri) {
        return new FNOLPayload(
                "POL-AUTO-112233", LocalDate.of(2026, 8, 16), "CA",
                "Front bumper impact with barrier",
                "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of(uri), null);
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
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("FRONT_BUMPER", "MODERATE", 4200, 0.91)));

        DamageAssessment result = provider.assess(payloadWithUri("data:image/jpeg;base64,/9j/4AAQSkZJRg=="));

        assertThat(result.damageType()).isEqualTo("FRONT_BUMPER");
        assertThat(result.severity()).isEqualTo("MODERATE");
        assertThat(result.estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(result.confidence()).isEqualByComparingTo("0.91");
        assertThat(result.photoQuality()).isEqualTo("CLEAR");
        assertThat(result.damageTypeProbabilities().get("FRONT_BUMPER")).isEqualByComparingTo("0.91");
    }

    @Test
    @DisplayName("Parses Gemini response for an image downloaded from an allow-listed https host")
    void assess_withAllowedHost_parsesResponseCorrectly() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("REAR_BUMPER", "SEVERE", 12500, 0.88)));

        DamageAssessment result = provider.assess(payloadWithUri("https://example.com/car-damage.jpg"));

        assertThat(result.damageType()).isEqualTo("REAR_BUMPER");
        assertThat(result.estimatedDamage()).isEqualByComparingTo("12500");
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
        assertThat(result.estimatedDamage()).isEqualByComparingTo("850");
    }

    @Test
    @DisplayName("Falls back gracefully when Gemini API returns an error")
    void assess_geminiApiError_returnsFallbackAssessment() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        DamageAssessment result = provider.assess(payloadWithUri("https://example.com/photo.jpg"));

        assertThat(result.damageType()).isEqualTo("UNKNOWN");
        assertThat(result.confidence()).isEqualByComparingTo("0.60");
    }

    @Test
    @DisplayName("Falls back gracefully when no evidenceUris are provided")
    void assess_noImageProvided_returnsFallbackAssessment() {
        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233", LocalDate.of(2026, 8, 16), "CA",
                "No photo available", null, null, null, List.of(), null);

        DamageAssessment result = provider.assess(payload);

        assertThat(result.confidence()).isEqualByComparingTo("0.60");
        assertThat(result.photoQuality()).isEqualTo("UNUSABLE");
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Sends the prompt in the body and the API key in x-goog-api-key, never in the URL")
    void assess_sendsKeyInHeaderNotUrl() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("SIDE_PANEL", "MODERATE", 3100, 0.82)));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        provider.assess(payloadWithUri("https://example.com/side.jpg"));
        verify(restTemplate).postForEntity(url.capture(), captor.capture(), eq(String.class));

        assertThat(url.getValue()).contains("gemini-2.5-flash").doesNotContain("key=").doesNotContain("test-api-key");
        assertThat(captor.getValue().getHeaders().getFirst("x-goog-api-key")).isEqualTo("test-api-key");
        assertThat(captor.getValue().getBody().toString()).contains("damage assessor");
        // JSON-only output with thinking off, so the call stays well inside the read timeout.
        assertThat(captor.getValue().getBody().toString())
                .contains("responseMimeType=application/json")
                .contains("thinkingBudget=0");
    }

    @Test
    @DisplayName("Sends every usable photo in one request, each labelled with its angle; refused ones are skipped")
    @SuppressWarnings("unchecked")
    void assess_sendsAllPhotosLabelledByAngle() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(geminiResponse("MULTIPLE", "MODERATE", 5200, 0.88)));
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.of(2026, 8, 16), "CA", "Side swipe",
                "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of("data:image/jpeg;base64,/9j/4AAQ", "https://example.com/rear.jpg", "file:///etc/passwd",
                        "data:image/png;base64,iVBORw0KGgo="),
                "WEB", null, null, null, List.of("FRONT", "REAR", "LEFT", "close_up"));

        DamageAssessment result = provider.assess(payload);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
        List<Map<String, Object>> parts = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) body.get("contents")).get(0).get("parts");
        // prompt + (label, image) for FRONT, REAR and CLOSE_UP; the file: URI is refused and skipped
        assertThat(parts).hasSize(7);
        assertThat(parts.get(1).get("text")).isEqualTo("Photo 1 — angle: FRONT");
        assertThat(parts.get(3).get("text")).isEqualTo("Photo 2 — angle: REAR");
        assertThat(parts.get(5).get("text")).isEqualTo("Photo 4 — angle: CLOSE_UP");
        assertThat(parts.stream().filter(p -> p.containsKey("inline_data"))).hasSize(3);
        assertThat(result.damageType()).isEqualTo("MULTIPLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/admin.jpg",
            "http://169.254.169.254/latest/meta-data",
            "file:///etc/passwd",
            "mock://photo/front-bumper.jpg",
            "https://not-allowed.example.org/photo.jpg"
    })
    @DisplayName("D5: refused evidence returns the fallback assessment and never calls Gemini")
    void assess_refusedEvidence_fallsBackWithoutCallingGemini(String uri) {
        DamageAssessment result = provider.assess(payloadWithUri(uri));

        assertThat(result.confidence()).isEqualByComparingTo("0.60");
        assertThat(result.photoQuality()).isEqualTo("UNUSABLE");
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Without an API key no request is sent to Gemini")
    void assess_noApiKey_fallsBack() {
        ReflectionTestUtils.setField(provider, "apiKey", "");

        DamageAssessment result = provider.assess(payloadWithUri("data:image/png;base64,iVBORw0KGgo="));

        assertThat(result.confidence()).isEqualByComparingTo("0.60");
        verifyNoInteractions(restTemplate);
    }
}
