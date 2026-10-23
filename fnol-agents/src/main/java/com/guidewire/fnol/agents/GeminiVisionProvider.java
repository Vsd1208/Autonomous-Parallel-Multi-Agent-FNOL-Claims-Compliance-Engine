package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.EvidenceImageLoader.EvidenceRejectedException;
import com.guidewire.fnol.agents.EvidenceImageLoader.ImageData;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Sends car damage photos to Google Gemini and parses the structured JSON response into a
 * {@link DamageAssessment} used by the reserve calculation pipeline.
 *
 * Activation: {@code gemini.vision.enabled=true} (default). Evidence is loaded through
 * {@link EvidenceImageLoader}, which accepts only image data URIs and allow-listed https hosts.
 * The provider falls back to a fixed low-confidence assessment (routing the claim to a human) when
 * there is no image, the evidence is refused, no API key is set, or Gemini fails, so Branch A
 * never crashes the pipeline.
 */
@Component
@ConditionalOnProperty(name = "gemini.vision.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiVisionProvider implements VisionProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionProvider.class);

    // The API key travels in the x-goog-api-key header, never in the URL (URLs end up in logs).
    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

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

    @Value("${gemini.vision.model:gemini-2.5-flash}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EvidenceImageLoader evidenceLoader;

    public GeminiVisionProvider(@Qualifier(GeminiVisionConfig.GEMINI_REST_TEMPLATE) RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                EvidenceImageLoader evidenceLoader) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.evidenceLoader = evidenceLoader;
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        List<String> uris = payload.evidenceUris();
        if (uris == null || uris.isEmpty()) {
            log.warn("[GeminiVision] No evidenceUris provided — using deterministic fallback assessment.");
            return fallback("NO_IMAGE");
        }

        String uri = uris.get(0);
        ImageData image;
        try {
            image = evidenceLoader.load(uri);
        } catch (EvidenceRejectedException e) {
            log.warn("[GeminiVision] Evidence refused ({}): {}", EvidenceImageLoader.describe(uri), e.getMessage());
            return fallback("EVIDENCE_REJECTED");
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[GeminiVision] gemini.api-key is not set — using fallback assessment.");
            return fallback("NO_API_KEY");
        }

        log.info("[GeminiVision] Sending image to Gemini {} — source={}", model, EvidenceImageLoader.describe(uri));
        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("text", DAMAGE_ASSESSMENT_PROMPT),
                                    Map.of("inline_data", Map.of("mime_type", image.mimeType(), "data", image.base64()))
                            )
                    ))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(GEMINI_API_URL.formatted(model), request, String.class);
            return parseGeminiResponse(response.getBody());

        } catch (RestClientException e) {
            log.error("[GeminiVision] Gemini API call failed — using fallback assessment. Cause: {}", e.getClass().getSimpleName());
            return fallback("API_ERROR");
        } catch (Exception e) {
            log.error("[GeminiVision] Unexpected error during vision assessment — using fallback. Cause: {}", e.getMessage());
            return fallback("PARSE_ERROR");
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

        Map<String, BigDecimal> probabilities = new java.util.LinkedHashMap<>();
        JsonNode probNode = assessment.path("damageTypeProbabilities");
        if (probNode.isObject()) {
            probNode.fields().forEachRemaining(entry ->
                probabilities.put(entry.getKey(), BigDecimal.valueOf(entry.getValue().asDouble()))
            );
        }

        log.info("[GeminiVision] Assessment — type={}, severity={}, estimate={}, confidence={}, photoQuality={}",
                damageType, severity, estimatedDamage, confidence, photoQuality);

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
     * Deterministic fallback for when no usable image is available or Gemini fails.
     * Confidence 0.60 is below the STP threshold, so the claim always goes to a human.
     */
    private DamageAssessment fallback(String reason) {
        log.warn("[GeminiVision] Using fallback assessment — reason={}", reason);
        boolean noUsableImage = reason.equals("NO_IMAGE") || reason.equals("EVIDENCE_REJECTED");
        return new DamageAssessment(
                "UNKNOWN", "MODERATE", new BigDecimal("3500"), new BigDecimal("0.60"),
                noUsableImage ? "UNUSABLE" : "UNKNOWN",
                Map.of("UNKNOWN", BigDecimal.ONE)
        );
    }
}
