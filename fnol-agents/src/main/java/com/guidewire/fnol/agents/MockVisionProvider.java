package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class MockVisionProvider implements VisionProvider {
    @Override
    public DamageAssessment assess(FNOLPayload payload) {
        return new DamageAssessment("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
    }
}
