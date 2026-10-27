package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic vision provider used when {@code gemini.vision.enabled=false} (tests and demos).
 *
 * <p>Keywords in the first evidence URI, when it is a {@code mock://} URI, pick the assessment
 * (uploaded photos and https URLs always get the normal one):
 * <ul>
 *   <li>{@code blurry} — FRONT_BUMPER, $4,200, confidence 0.61 (fails the confidence factor)</li>
 *   <li>{@code severe} — MULTIPLE, $24,000, SEVERE (HIGH severity, always a human)</li>
 *   <li>{@code water}  — WATER_DAMAGE, $4,200 (home claims)</li>
 *   <li>{@code flood}  — FLOOD, $38,000, SEVERE (home claims; flood is excluded from homeowners policies)</li>
 *   <li>{@code slow}   — sleeps 12 s before answering, to exercise the 10 s orchestrator deadline</li>
 *   <li>{@code normal} or anything else — FRONT_BUMPER, $4,200, confidence 0.91</li>
 * </ul>
 * No evidence at all gives the same low-confidence fallback as the Gemini provider. On a HOME policy the
 * normal assessment is WATER_DAMAGE instead of FRONT_BUMPER.
 */
@Component
@ConditionalOnProperty(name = "gemini.vision.enabled", havingValue = "false")
public class MockVisionProvider implements VisionProvider {

    static final long SLOW_MILLIS = 12_000;

    private static final Set<String> PROPERTY_CAUSES = Set.of("WATER_DAMAGE", "FLOOD");

    /** Also reports the photo subject: the mock's photos always match its answer (BUILDING or VEHICLE). */
    @Override
    public DamageAssessment assess(FNOLPayload payload, String lineOfBusiness) {
        DamageAssessment result = assess(payload);
        if ("HOME".equalsIgnoreCase(lineOfBusiness) && "FRONT_BUMPER".equals(result.damageType())
                && "CLEAR".equals(result.photoQuality())) {
            result = water();
        }
        if ("UNUSABLE".equals(result.photoQuality())) {
            return result;   // no usable photo: nothing to report
        }
        return result.withPhotoSubject(PROPERTY_CAUSES.contains(result.damageType()) ? "BUILDING" : "VEHICLE");
    }

    private static DamageAssessment water() {
        return new DamageAssessment(
                "WATER_DAMAGE", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.90"),
                "CLEAR",
                Map.of("WATER_DAMAGE", new BigDecimal("0.90"), "UNKNOWN", new BigDecimal("0.10")));
    }

    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        List<String> uris = payload.evidenceUris();
        if (uris == null || uris.isEmpty() || uris.get(0) == null) {
            return new DamageAssessment("UNKNOWN", "MODERATE", new BigDecimal("3500"), new BigDecimal("0.60"),
                    "UNUSABLE", Map.of("UNKNOWN", BigDecimal.ONE));
        }
        // Keywords are read only from mock:// URIs. An uploaded photo (data: URI) is a long base64
        // string that could contain "slow" or "water" by chance, so it always gets the normal assessment.
        String first = uris.get(0).toLowerCase(Locale.ROOT);
        String uri = first.startsWith("mock://") ? first : "";

        if (uri.contains("slow")) {
            try {
                Thread.sleep(SLOW_MILLIS);
            } catch (InterruptedException e) {
                // The orchestrator cancelled this branch after its deadline; give the thread back.
                Thread.currentThread().interrupt();
            }
        }
        if (uri.contains("blurry")) {
            return new DamageAssessment(
                    "FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.61"),
                    "BLURRY",
                    Map.of("FRONT_BUMPER", new BigDecimal("0.61"), "UNKNOWN", new BigDecimal("0.39")));
        }
        if (uri.contains("severe")) {
            return new DamageAssessment(
                    "MULTIPLE", "SEVERE", new BigDecimal("24000"), new BigDecimal("0.93"),
                    "CLEAR",
                    Map.of("MULTIPLE", new BigDecimal("0.93"), "FRONT_BUMPER", new BigDecimal("0.07")));
        }
        if (uri.contains("water")) {
            return water();
        }
        if (uri.contains("flood")) {
            return new DamageAssessment(
                    "FLOOD", "SEVERE", new BigDecimal("38000"), new BigDecimal("0.92"),
                    "CLEAR",
                    Map.of("FLOOD", new BigDecimal("0.92"), "WATER_DAMAGE", new BigDecimal("0.08")));
        }
        return new DamageAssessment(
                "FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"),
                "CLEAR",
                Map.of("FRONT_BUMPER", new BigDecimal("0.91"), "SIDE_PANEL", new BigDecimal("0.06"),
                       "REAR_BUMPER", new BigDecimal("0.03")));
    }
}
