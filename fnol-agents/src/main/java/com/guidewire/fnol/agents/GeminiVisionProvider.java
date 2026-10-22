package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Real AI vision provider for Branch A (Operational AI).
 *
 * Sends car damage photos to Google Gemini 2.5 Flash and parses the structured
 * JSON response into a {@link DamageAssessment} used by the Guidewire reserve
 * calculation pipeline.
 *
 * Activation: set {@code gemini.vision.enabled=true} in application.yml (default).
 * Falls back gracefully to a fixed assessment if no image URI is supplied or if
 * the Gemini API is unavailable, ensuring Branch A never crashes the pipeline.
 *
 * Supports both:
 * <ul>
 *   <li>Base64-encoded images: {@code data:image/jpeg;base64,...}</li>
 *   <li>Public image URLs: {@code https://example.com/photo.jpg}</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "gemini.vision.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionProvider.class);

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private static final String DAMAGE_ASSESSMENT_PROMPT = """
            You are an automotive insurance damage assessor for Guidewire ClaimCenter.
            Analyze this vehicle damage photo and respond ONLY with a valid JSON object in this exact format:
            {
              "damageType": "FRONT_BUMPER | REAR_BUMPER | WINDSHIELD | SIDE_PANEL | ROOF | MULTIPLE | UNKNOWN",
              "severity": "MINOR | MODERATE | SEVERE",
              "estimatedDamage": <integer USD repair cost estimate>,
              "confidence": <decimal 0.0 to 1.0>,
              "photoQuality": "CLEAR | BLURRY | PARTIAL | UNUSABLE",
              "damageTypeProbabilities": {
                "FRONT_BUMPER": <0.0-1.0>,
                "REAR_BUMPER": <0.0-1.0>,
                "WINDSHIELD": <0.0-1.0>,
                "SIDE_PANEL": <0.0-1.0>,
                "ROOF": <0.0-1.0>,
                "MULTIPLE": <0.0-1.0>,
                "UNKNOWN": <0.0-1.0>
              }
            }
            Rules:
            - damageType must be exactly one of the listed enum values (the highest-probability type).
            - severity must be MINOR (< $2,000), MODERATE ($2,000–$10,000), or SEVERE (> $10,000).
            - estimatedDamage must be a plain integer (no $ sign, no commas).
            - confidence reflects your overall certainty in the entire assessment (1.0 = fully confident).
            - photoQuality: CLEAR = sharp and well-lit, BLURRY = out of focus, PARTIAL = partially visible, UNUSABLE = cannot assess.
            - damageTypeProbabilities must sum to approximately 1.0 across all types.
            - Do NOT include any explanation, markdown, or text outside the JSON object.
            """;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.vision.model:gemini-1.5-flash}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GeminiVisionProvider(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        List<String> uris = payload.evidenceUris();
        if (uris == null || uris.isEmpty()) {
            log.warn("[GeminiVision] No evidenceUris provided — using deterministic fallback assessment.");
            return fallback("NO_IMAGE");
        }

        String uri = uris.get(0);
        log.info("[GeminiVision] Sending image to Gemini {} — uri prefix={}", model, uri.substring(0, Math.min(40, uri.length())));

        try {
            Object imagePart = buildImagePart(uri);
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("text", DAMAGE_ASSESSMENT_PROMPT),
                                    imagePart
                            )
                    ))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            String url = GEMINI_API_URL.formatted(model, apiKey);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            return parseGeminiResponse(response.getBody());

        } catch (RestClientException e) {
            log.error("[GeminiVision] Gemini API call failed — using fallback assessment. Cause: {}", e.getMessage());
            return fallback("API_ERROR");
        } catch (Exception e) {
            log.error("[GeminiVision] Unexpected error during vision assessment — using fallback. Cause: {}", e.getMessage());
            return fallback("PARSE_ERROR");
        }
    }

    /**
     * Builds the Gemini API image part.
     * Accepts both base64 data URIs (data:image/jpeg;base64,...) and public HTTPS image URLs.
     */
    private Object buildImagePart(String uri) {
        if (uri.startsWith("data:")) {
            // Base64-encoded image: data:image/jpeg;base64,<data>
            int commaIdx = uri.indexOf(',');
            String mimeAndEncoding = uri.substring(5, commaIdx); // e.g. "image/jpeg;base64"
            String mimeType = mimeAndEncoding.split(";")[0];     // e.g. "image/jpeg"
            String base64Data = uri.substring(commaIdx + 1);
            return Map.of("inline_data", Map.of(
                    "mime_type", mimeType,
                    "data", base64Data
            ));
        } else {
            // Public image URL (https:// or http://)
            // Download and convert to base64 inline_data — Gemini's file_data only works
            // with Google Cloud Storage URIs, not arbitrary public URLs.
            try {
                byte[] imageBytes = restTemplate.getForObject(uri, byte[].class);
                String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
                // Detect mime type from URL extension, default to image/jpeg
                String mime = "image/jpeg";
                if (uri.toLowerCase().endsWith(".png"))  mime = "image/png";
                if (uri.toLowerCase().endsWith(".webp")) mime = "image/webp";
                if (uri.toLowerCase().endsWith(".gif"))  mime = "image/gif";
                return Map.of("inline_data", Map.of("mime_type", mime, "data", base64));
            } catch (Exception e) {
                log.warn("[GeminiVision] Could not download image from URL '{}' — falling back to URL-as-text hint. Cause: {}", uri, e.getMessage());
                // Last resort: pass the URL as a text hint in the prompt
                return Map.of("text", "Image URL (describe damage as if viewed): " + uri);
            }
        }
    }

    /**
     * Parses the Gemini API response JSON and extracts the DamageAssessment.
     * Gemini returns: { "candidates": [{ "content": { "parts": [{ "text": "<json>" }] } }] }
     */
    private DamageAssessment parseGeminiResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String text = root
                .path("candidates").get(0)
                .path("content")
                .path("parts").get(0)
                .path("text").asText();

        // Strip markdown code fences if Gemini wraps the JSON in ```json ... ```
        text = text.strip();
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }

        log.debug("[GeminiVision] Raw Gemini JSON response: {}", text);

        JsonNode assessment = objectMapper.readTree(text);
        String damageType   = assessment.path("damageType").asText("UNKNOWN");
        String severity     = assessment.path("severity").asText("MODERATE");
        int estimatedDamage = assessment.path("estimatedDamage").asInt(3500);
        double confidence   = assessment.path("confidence").asDouble(0.75);
        String photoQuality = assessment.path("photoQuality").asText("UNKNOWN");

        // Parse damageTypeProbabilities map
        Map<String, BigDecimal> probabilities = new java.util.LinkedHashMap<>();
        JsonNode probNode = assessment.path("damageTypeProbabilities");
        if (probNode.isObject()) {
            probNode.fields().forEachRemaining(entry ->
                probabilities.put(entry.getKey(), BigDecimal.valueOf(entry.getValue().asDouble()))
            );
        }

        log.info("[GeminiVision] Assessment — type={}, severity={}, estimate={}, confidence={}, photoQuality={}",
                damageType, severity, estimatedDamage, confidence, photoQuality);
        log.debug("[GeminiVision] Damage type probabilities: {}", probabilities);

        return new DamageAssessment(
                damageType,
                severity,
                new BigDecimal(estimatedDamage),
                BigDecimal.valueOf(confidence),
                photoQuality,
                probabilities
        );
    }

    /**
     * Deterministic fallback for when Gemini is unavailable or no image is provided.
     * Clearly labelled so the adjuster advisory can surface the fallback reason.
     */
    private DamageAssessment fallback(String reason) {
        log.warn("[GeminiVision] Using fallback assessment — reason={}", reason);
        // Low confidence (0.60) ensures ExplanationBuilder will route to HUMAN_REVIEW
        // photoQuality = UNUSABLE signals the adjuster that no valid image was processed
        return new DamageAssessment(
                "UNKNOWN", "MODERATE", new BigDecimal("3500"), new BigDecimal("0.60"),
                reason.equals("NO_IMAGE") ? "UNUSABLE" : "UNKNOWN",
                Map.of("UNKNOWN", BigDecimal.ONE)
        );
    }
}
