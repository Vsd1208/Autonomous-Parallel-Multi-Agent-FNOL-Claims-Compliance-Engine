package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.SecondOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * Calibrated AI confidence for vehicle claims ("trust score"): the probability that the damaged area Gemini named is
 * right, learned from labelled photos by tools/vision-eval/trust_score.py. Gemini's self-reported confidence is
 * nearly constant and does not separate right from wrong answers; this score uses the signals that do: the gap
 * between Gemini's two highest damage-type probabilities and the second-opinion model's agreement (a margin-only
 * model is used when the second opinion is unavailable).
 *
 * <p>Active only when {@code trust-score.json} is on the classpath and was measured with the app's vehicle prompt,
 * model and thinking budget on at least 50 images; otherwise confidence passes through unchanged. It replaces
 * {@code confidence} (the STP confidence factor and the WHY panel use it) and keeps Gemini's own value in
 * {@code rawConfidence}. Home claims are not scored: the model was trained on car photos only.
 */
@Component
public class TrustScorer {

    private static final Logger log = LoggerFactory.getLogger(TrustScorer.class);
    static final String RESOURCE = "trust-score.json";
    static final int MIN_IMAGES = 50;
    private static final Set<String> SPECIFIC = Set.of("FRONT_BUMPER", "REAR_BUMPER", "SIDE_PANEL", "WINDSHIELD", "ROOF");

    private final Model full;
    private final Model marginOnly;

    /**
     * {@code margin} weighs the probability margin of every answer (trust-score files up to version 2);
     * {@code namedMargin} weighs it only when Gemini names a specific area (version 3: for a "multiple areas" or
     * UNKNOWN answer the margin says nothing about whether an area is right).
     */
    record Model(double margin, double namedMargin, double agreement, double specific, double intercept) {
        double score(double m, double a, double s) {
            return 1 / (1 + Math.exp(-(intercept + margin * m + namedMargin * m * s + agreement * a + specific * s)));
        }
    }

    @Autowired
    public TrustScorer(@Value("${gemini.vision.trust-score.enabled:true}") boolean enabled,
                       @Value("${gemini.vision.model:gemini-2.5-flash}") String model,
                       @Value("${gemini.vision.thinking-budget:0}") int thinkingBudget) {
        this(enabled ? read() : null, model, thinkingBudget);
    }

    TrustScorer(JsonNode json, String model, int thinkingBudget) {
        Model[] loaded = load(json, model, thinkingBudget);
        this.full = loaded[0];
        this.marginOnly = loaded[1];
    }

    /** A scorer that leaves confidence unchanged. */
    public static TrustScorer none() {
        return new TrustScorer(null, null, 0);
    }

    public boolean isActive() {
        return full != null;
    }

    /** The assessment with its confidence replaced by the trust score, or unchanged when the scorer does not apply. */
    public DamageAssessment apply(DamageAssessment d, SecondOpinion second, String lineOfBusiness) {
        if (!isActive() || !"AUTO".equalsIgnoreCase(lineOfBusiness)
                || d.damageTypeProbabilities() == null || d.damageTypeProbabilities().isEmpty()) {
            return d;   // not configured, a home claim, or a fallback assessment without probabilities
        }
        double margin = VisionConfidenceCalibrator.margin(d.damageTypeProbabilities()).doubleValue();
        boolean specific = SPECIFIC.contains(d.damageType());
        BigDecimal agreement = second == null ? null : second.probabilityOf(d.damageType());
        double score = second != null
                ? full.score(margin, specific && agreement != null ? agreement.doubleValue() : 0, specific ? 1 : 0)
                : marginOnly.score(margin, 0, specific ? 1 : 0);
        BigDecimal raw = d.rawConfidence() != null ? d.rawConfidence() : d.confidence();
        return new DamageAssessment(d.damageType(), d.severity(), d.estimatedDamage(),
                BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP), d.photoQuality(),
                d.damageTypeProbabilities(), raw, d.photoSubject(), d.privacy(), d.visionSource());
    }

    private static Model[] load(JsonNode json, String model, int thinkingBudget) {
        if (json == null) {
            return new Model[]{null, null};
        }
        String forPrompt = json.path("promptSha256").asText("");
        String problem = !forPrompt.equals(VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA) ? "vehicle prompt " + forPrompt
                : !json.path("model").asText().equals(model) ? "model " + json.path("model").asText()
                : json.path("thinkingBudget").asInt(-1) != thinkingBudget ? "thinking budget " + json.path("thinkingBudget").asInt(-1)
                : json.path("images").asInt(0) < MIN_IMAGES ? json.path("images").asInt(0) + " images" : null;
        if (problem != null) {
            log.warn("[GeminiVision] Trust score not used: it was measured with {} (app: prompt {}, model {}, thinking "
                    + "budget {}, at least {} images). Re-run tools/vision-eval/trust_score.py.",
                    problem, VisionConfidenceCalibrator.VEHICLE_PROMPT_SHA, model, thinkingBudget, MIN_IMAGES);
            return new Model[]{null, null};
        }
        Model full = model(json.path("withSecondOpinion"));
        Model margin = model(json.path("marginOnly"));
        log.info("[GeminiVision] Trust score active ({} images; AUC {} vs Gemini's own {})", json.path("images").asInt(),
                json.path("metrics").path("aucTrust").asText(), json.path("metrics").path("aucRaw").asText());
        return new Model[]{full, margin};
    }

    private static Model model(JsonNode node) {
        Map<String, Double> c = new java.util.HashMap<>();
        for (int i = 0; i < node.path("features").size(); i++) {
            c.put(node.path("features").get(i).asText(), node.path("coef").get(i).asDouble());
        }
        return new Model(c.getOrDefault("margin", 0.0), c.getOrDefault("namedMargin", 0.0),
                c.getOrDefault("agreement", 0.0), c.getOrDefault("specific", 0.0), node.path("intercept").asDouble());
    }

    private static JsonNode read() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.info("[GeminiVision] No {} on the classpath; Gemini's confidence is used (see tools/vision-eval).", RESOURCE);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            log.warn("[GeminiVision] Could not read {}: {}", RESOURCE, e.getMessage());
            return null;
        }
    }
}
