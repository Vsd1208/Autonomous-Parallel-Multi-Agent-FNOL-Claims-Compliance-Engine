
package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Mock vision provider used in test environments and local development when
 * {@code gemini.vision.enabled=false}. Returns a deterministic, hardcoded
 * {@link DamageAssessment} so that all unit and integration tests remain
 * fast and API-key-free.
 *
 * In production / demo mode, {@link GeminiVisionProvider} takes precedence
 * (activated by {@code gemini.vision.enabled=true}).
 */
@Component
@ConditionalOnProperty(name = "gemini.vision.enabled", havingValue = "false")
public class MockVisionProvider implements VisionProvider {

    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        boolean blurry = payload.evidenceUris() != null &&
                payload.evidenceUris().stream().anyMatch(u -> u.contains("blurry"));
        if (blurry) {
            return new DamageAssessment(
                    "FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.61"),
                    "BLURRY",
                    Map.of("FRONT_BUMPER", new BigDecimal("0.61"), "UNKNOWN", new BigDecimal("0.39"))
            );
        }
        return new DamageAssessment(
                "FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"),
                "CLEAR",
                Map.of("FRONT_BUMPER", new BigDecimal("0.91"), "SIDE_PANEL", new BigDecimal("0.06"),
                       "REAR_BUMPER", new BigDecimal("0.03"))
        );
    }
}