package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.EvidenceImageLoader.EvidenceRejectedException;
import com.guidewire.fnol.agents.EvidenceImageLoader.ImageData;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.PhotoPrivacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Sends vehicle or property damage photos (by the policy's line of business) to Google Gemini and parses the structured JSON response into a
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

    static final String DAMAGE_ASSESSMENT_PROMPT = """
            You are an automotive insurance damage assessor for Guidewire ClaimCenter.
            You receive one or more photos of the same vehicle, each labelled with its angle (FRONT, REAR, LEFT,
            RIGHT, CLOSE_UP, ODOMETER_VIN, OTHER). Assess the damage from this incident across all of them: use the
            close-up for severity and the side views for the extent. If damage is spread over several areas, the
            damageType is MULTIPLE. Rate photoQuality by the photos that show the damage.
            First report what the photos actually show, whatever you were told to expect (photoSubject).
            Respond ONLY with a valid JSON object in this exact format:
            {
              "photoSubject": "VEHICLE | BUILDING | OTHER_PROPERTY | NONE",
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
            - photoSubject: VEHICLE = a car, van, truck or motorcycle; BUILDING = a house or other building, inside or
              outside; OTHER_PROPERTY = anything else (fence, boat, garden, contents); NONE = no damaged property.
            - damageType must be exactly one of the listed enum values (the highest-probability type).
            - severity must be MINOR (< $2,000), MODERATE ($2,000–$10,000), or SEVERE (> $10,000).
            - estimatedDamage must be a plain integer (no $ sign, no commas).
            - confidence reflects your overall certainty in the entire assessment (1.0 = fully confident).
            - photoQuality: CLEAR = sharp and well-lit, BLURRY = out of focus, PARTIAL = partially visible, UNUSABLE = cannot assess.
            - damageTypeProbabilities must sum to approximately 1.0 across all types.
            - Do NOT include any explanation, markdown, or text outside the JSON object.
            """;

    /**
     * Home claims. The cause of loss decides coverage: flood is excluded from standard homeowners policies, so it
     * must not be confused with water from a burst pipe. Both prompts ask what the photos show (photoSubject) so a
     * photo of the wrong kind of property is caught whichever prompt the policy selects.
     */
    static final String PROPERTY_ASSESSMENT_PROMPT = """
            You are a homeowners insurance property damage assessor for Guidewire ClaimCenter.
            You receive one or more photos of the same property, each labelled with its angle (OVERVIEW, CLOSE_UP,
            OTHER = the source of the damage). Identify the cause of loss and assess the damage across all of them.
            First report what the photos actually show, whatever you were told to expect (photoSubject).
            Causes of loss:
            - WATER_DAMAGE: water released inside the building, e.g. a burst or leaking pipe or appliance.
            - FLOOD: rising surface water from outside (river, storm surge, flash flood) entering or surrounding the building.
            - FIRE_SMOKE: fire, smoke or soot damage, including wildfire.
            - WIND_STORM: roof, siding or windows damaged by wind, hurricane, tornado or hail.
            - FALLEN_TREE: a tree or large limb fell onto the building.
            - MULTIPLE: two or more of these causes. UNKNOWN: no property damage can be seen.
            Respond ONLY with a valid JSON object in this exact format:
            {
              "photoSubject": "VEHICLE | BUILDING | OTHER_PROPERTY | NONE",
              "damageType": "WATER_DAMAGE | FLOOD | FIRE_SMOKE | WIND_STORM | FALLEN_TREE | MULTIPLE | UNKNOWN",
              "severity": "MINOR | MODERATE | SEVERE",
              "estimatedDamage": <integer USD repair or rebuild cost estimate>,
              "confidence": <decimal 0.0 to 1.0>,
              "photoQuality": "CLEAR | BLURRY | PARTIAL | UNUSABLE",
              "damageTypeProbabilities": {
                "WATER_DAMAGE": <0.0-1.0>,
                "FLOOD": <0.0-1.0>,
                "FIRE_SMOKE": <0.0-1.0>,
                "WIND_STORM": <0.0-1.0>,
                "FALLEN_TREE": <0.0-1.0>,
                "MULTIPLE": <0.0-1.0>,
                "UNKNOWN": <0.0-1.0>
              }
            }
            Rules:
            - photoSubject: VEHICLE = a car, van, truck or motorcycle; BUILDING = a house or other building, inside or
              outside; OTHER_PROPERTY = anything else (fence, boat, garden, contents); NONE = no damaged property.
            - damageType must be exactly one of the listed enum values (the highest-probability type).
            - severity must be MINOR (< $2,000), MODERATE ($2,000–$10,000), or SEVERE (> $10,000).
            - estimatedDamage must be a plain integer (no $ sign, no commas). A destroyed home is a full rebuild.
            - confidence reflects your overall certainty in the entire assessment (1.0 = fully confident).
            - photoQuality: CLEAR = sharp and well-lit, BLURRY = out of focus, PARTIAL = partially visible, UNUSABLE = cannot assess.
            - damageTypeProbabilities must sum to approximately 1.0 across all types.
            - Do NOT include any explanation, markdown, or text outside the JSON object.
            """;

    /** Photos per Gemini request (the standard set is five) and their combined size (inline limit is 20 MB). */
    static final int MAX_IMAGES = 6;
    /** Photo angles never sent to Gemini (identifying, no damage to assess). */
    static final java.util.Set<String> NOT_SENT_ANGLES = java.util.Set.of("ODOMETER_VIN");
    static final long MAX_TOTAL_BYTES = 15L * 1024 * 1024;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.vision.model:gemini-2.5-flash}")
    private String model;

    /**
     * Gemini 2.5 "thinking" tokens. 0 (default) turns thinking off: the structured damage JSON does not
     * need it, and thinking can push a call past the read timeout, which forces the fallback assessment.
     */
    @Value("${gemini.vision.thinking-budget:0}")
    private int thinkingBudget;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EvidenceImageLoader evidenceLoader;
    private final VisionConfidenceCalibrator calibrator;
    private final PhotoRedactor redactor;

    @Autowired
    public GeminiVisionProvider(@Qualifier(GeminiVisionConfig.GEMINI_REST_TEMPLATE) RestTemplate restTemplate,
                                ObjectMapper objectMapper,
                                EvidenceImageLoader evidenceLoader,
                                VisionConfidenceCalibrator calibrator,
                                PhotoRedactor redactor) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.evidenceLoader = evidenceLoader;
        this.calibrator = calibrator;
        this.redactor = redactor;
    }

    /** Without confidence calibration (Gemini's confidence is used as reported). */
    public GeminiVisionProvider(RestTemplate restTemplate, ObjectMapper objectMapper, EvidenceImageLoader evidenceLoader) {
        this(restTemplate, objectMapper, evidenceLoader, VisionConfidenceCalibrator.none(), PhotoRedactor.disabled());
    }

    /** With calibration and no redaction (tests). */
    public GeminiVisionProvider(RestTemplate restTemplate, ObjectMapper objectMapper, EvidenceImageLoader evidenceLoader,
                                VisionConfidenceCalibrator calibrator) {
        this(restTemplate, objectMapper, evidenceLoader, calibrator, PhotoRedactor.disabled());
    }

    /** With a given redactor and no calibration (tests). */
    public GeminiVisionProvider(RestTemplate restTemplate, ObjectMapper objectMapper, EvidenceImageLoader evidenceLoader,
                                PhotoRedactor redactor) {
        this(restTemplate, objectMapper, evidenceLoader, VisionConfidenceCalibrator.none(), redactor);
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        return assess(payload, "AUTO");
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload, String lineOfBusiness) {
        String prompt = "HOME".equalsIgnoreCase(lineOfBusiness) ? PROPERTY_ASSESSMENT_PROMPT : DAMAGE_ASSESSMENT_PROMPT;
        List<String> uris = payload.evidenceUris();
        if (uris == null || uris.isEmpty()) {
            log.warn("[GeminiVision] No evidenceUris provided — using deterministic fallback assessment.");
            return fallback("NO_IMAGE");
        }

        // Every usable photo, labelled with its angle, up to MAX_IMAGES and MAX_TOTAL_BYTES per request.
        List<String> labels = new java.util.ArrayList<>();
        List<String> images = new java.util.ArrayList<>();      // data URIs
        long totalBytes = 0;
        int withheld = 0;
        for (int i = 0; i < uris.size() && images.size() < MAX_IMAGES; i++) {
            String uri = uris.get(i);
            if (uri == null || uri.isBlank()) continue;
            // Data minimisation: the odometer / VIN photo identifies the vehicle and its owner and shows no damage,
            // so it stays with the insurer and is never sent to the external model.
            if (NOT_SENT_ANGLES.contains(payload.angleOf(i))) {
                withheld++;
                continue;
            }
            try {
                ImageData image = evidenceLoader.load(uri);
                long bytes = (long) image.base64().length() * 3 / 4;
                if (totalBytes + bytes > MAX_TOTAL_BYTES) {
                    log.warn("[GeminiVision] Photo {} skipped: the request would exceed {} bytes", i + 1, MAX_TOTAL_BYTES);
                    continue;
                }
                totalBytes += bytes;
                labels.add("Photo " + (i + 1) + " — angle: " + payload.angleOf(i));
                images.add("data:" + image.mimeType() + ";base64," + image.base64());
            } catch (EvidenceRejectedException e) {
                log.warn("[GeminiVision] Evidence refused ({}): {}", EvidenceImageLoader.describe(uri), e.getMessage());
            }
        }
        if (images.isEmpty()) {
            return fallback("EVIDENCE_REJECTED");
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[GeminiVision] gemini.api-key is not set — using fallback assessment.");
            return fallback("NO_API_KEY");
        }

        // Privacy: faces and licence plates are blurred before any photo leaves; never sent unblurred when required.
        PhotoRedactor.Redacted redacted;
        try {
            redacted = redactor.redact(images, lineOfBusiness);
        } catch (PhotoRedactor.RedactionUnavailableException e) {
            log.warn("[GeminiVision] {} — photos not sent to Gemini; the claim goes to an adjuster.", e.getMessage());
            return fallback("REDACTION_UNAVAILABLE");
        }
        PhotoPrivacy privacy = new PhotoPrivacy(images.size(), withheld, redacted.faces(), redacted.plates(),
                redactor.isEnabled());
        List<Map<String, Object>> imageParts = new java.util.ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            String dataUri = redacted.imageDataUris().get(i);
            int comma = dataUri.indexOf(',');
            String mime = dataUri.substring(5, dataUri.indexOf(';'));
            imageParts.add(Map.of("text", labels.get(i)));
            imageParts.add(Map.of("inline_data", Map.of("mime_type", mime, "data", dataUri.substring(comma + 1))));
        }

        log.info("[GeminiVision] Sending {} photo(s) to Gemini {} — source={}", imageParts.size() / 2, model,
                EvidenceImageLoader.describe(uris.get(0)));
        try {
            List<Map<String, Object>> parts = new java.util.ArrayList<>();
            parts.add(Map.of("text", prompt));
            parts.addAll(imageParts);
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", generationConfig()
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(GEMINI_API_URL.formatted(model), request, String.class);
            // The calibration map was measured on the vehicle prompt only; property answers keep Gemini's confidence.
            return parseGeminiResponse(response.getBody(),
                    prompt == DAMAGE_ASSESSMENT_PROMPT ? calibrator : VisionConfidenceCalibrator.none()).withPrivacy(privacy);

        } catch (RestClientException e) {
            log.error("[GeminiVision] Gemini API call failed — using fallback assessment. Cause: {}", e.getClass().getSimpleName());
            return fallback("API_ERROR");
        } catch (Exception e) {
            log.error("[GeminiVision] Unexpected error during vision assessment — using fallback. Cause: {}", e.getMessage());
            return fallback("PARSE_ERROR");
        }
    }

    /** JSON-only output; thinking budget only for 2.5 models (older models reject thinkingConfig). */
    private Map<String, Object> generationConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("responseMimeType", "application/json");
        config.put("temperature", 0);
        if (model != null && model.contains("2.5")) {
            config.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
        }
        return config;
    }

    /**
     * Parses the Gemini API response JSON and extracts the DamageAssessment.
     * Gemini returns: { "candidates": [{ "content": { "parts": [{ "text": "<json>" }] } }] }
     */
    private DamageAssessment parseGeminiResponse(String responseBody, VisionConfidenceCalibrator calibrator) throws Exception {
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
        String photoSubject = assessment.hasNonNull("photoSubject") ? assessment.path("photoSubject").asText() : null;

        Map<String, BigDecimal> probabilities = new java.util.LinkedHashMap<>();
        JsonNode probNode = assessment.path("damageTypeProbabilities");
        if (probNode.isObject()) {
            probNode.fields().forEachRemaining(entry ->
                probabilities.put(entry.getKey(), BigDecimal.valueOf(entry.getValue().asDouble()))
            );
        }

        // Gemini's confidence is self-reported; when a calibration map is active, replace it with the share
        // of answers that were actually right at that confidence on labelled photos (tools/vision-eval).
        BigDecimal raw = BigDecimal.valueOf(confidence);
        BigDecimal calibrated = calibrator.calibrate(raw, probabilities);
        log.info("[GeminiVision] Assessment — subject={}, type={}, severity={}, estimate={}, confidence={} (raw {}), photoQuality={}",
                photoSubject, damageType, severity, estimatedDamage, calibrated, raw, photoQuality);

        return new DamageAssessment(
                damageType,
                severity,
                new BigDecimal(estimatedDamage),
                calibrated,
                photoQuality,
                probabilities,
                calibrator.isActive() ? raw : null,
                photoSubject
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
