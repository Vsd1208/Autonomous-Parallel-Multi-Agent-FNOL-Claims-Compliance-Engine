package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.stereotype.Component;

@Component
public class VisionDamageAgent implements FNOLAgent<FNOLPayload, DamageAssessment> {
    private final VisionProvider provider;

    public VisionDamageAgent(VisionProvider provider) {
        this.provider = provider;
    }

    @Override
    public DamageAssessment execute(FNOLPayload input) {
        return provider.assess(input);
    }

    /** Vehicle or property damage, depending on the policy's line of business (AUTO, HOME). */
    public DamageAssessment execute(FNOLPayload input, String lineOfBusiness) {
        return provider.assess(input, lineOfBusiness);
    }
}
