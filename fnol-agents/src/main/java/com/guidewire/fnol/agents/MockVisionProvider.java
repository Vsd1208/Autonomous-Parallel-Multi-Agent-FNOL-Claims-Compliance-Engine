
package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class MockVisionProvider implements VisionProvider {

    @Override
    public DamageAssessment assess(FNOLPayload payload) {

        boolean blurry = payload.evidenceUris() != null &&
                payload.evidenceUris().stream()
                .anyMatch(uri -> uri != null &&
                        uri.toLowerCase().contains("blurry"));

        BigDecimal confidence;

        if (blurry) {
            confidence = new BigDecimal("0.61");
        } else {
            confidence = new BigDecimal("0.91");
        }

        return new DamageAssessment(
                "FRONT_BUMPER",
                "MODERATE",
                new BigDecimal("4200"),
                confidence
        );
    }
}