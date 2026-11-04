package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Answers with Gemini's recorded answer for a known photo, so a review with no model and no key still shows real model output.
 * It is not live analysis: a photo outside the recorded set gets the safe placeholder, whose low confidence sends it to a person.
 * {@code mock://} evidence, which only scripts send, keeps its canned answer. It makes no network call of any kind.
 */
@Component
@Primary
@ConditionalOnProperty(name = "fnol.vision.provider", havingValue = "replay")
public class ReplayVisionProvider implements VisionProvider {

    private final ReplayAnswers answers;
    private final MockVisionProvider canned = new MockVisionProvider();

    public ReplayVisionProvider(ReplayAnswers answers) {
        this.answers = answers;
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        return assess(payload, "AUTO");
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload, String lineOfBusiness) {
        List<String> uris = payload.evidenceUris();
        boolean scripted = uris != null && !uris.isEmpty() && uris.stream().allMatch(u -> u != null && u.toLowerCase(Locale.ROOT).startsWith("mock://"));
        if (scripted || uris == null || uris.isEmpty()) {
            return canned.assess(payload, lineOfBusiness);
        }
        return answers.find(uris).filter(a -> "AUTO".equalsIgnoreCase(lineOfBusiness))
                .map(a -> recorded(a.path("gemini"), answers.manifest()))
                .orElseGet(ReplayVisionProvider::placeholder);
    }

    /** The placeholder Gemini's own failure path gives: nothing was read, so the claim goes to a person. */
    static DamageAssessment placeholder() {
        return new DamageAssessment("UNKNOWN", "MODERATE", new BigDecimal("3500"), new BigDecimal("0.60"), "UNUSABLE",
                Map.of("UNKNOWN", BigDecimal.ONE));
    }

    static DamageAssessment recorded(JsonNode g, JsonNode manifest) {
        Map<String, BigDecimal> probabilities = new LinkedHashMap<>();
        g.path("damageTypeProbabilities").fields().forEachRemaining(e -> probabilities.put(e.getKey(), e.getValue().decimalValue()));
        String source = "replay of " + manifest.path("model").asText() + " prompt " + manifest.path("promptSha256").asText()
                + " thinking " + manifest.path("thinkingBudget").asText();
        BigDecimal confidence = g.path("confidence").decimalValue();
        return new DamageAssessment(g.path("damageType").asText("UNKNOWN"), g.hasNonNull("severity") ? g.path("severity").asText() : null,
                g.path("estimatedDamage").decimalValue(), confidence, g.path("photoQuality").asText("UNKNOWN"), probabilities,
                null, g.hasNonNull("photoSubject") ? g.path("photoSubject").asText() : null, null, source);
    }
}
