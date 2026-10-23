package com.guidewire.fnol.guardrails;

import com.guidewire.fnol.common.CoverageNotEligibleException;
import com.guidewire.fnol.common.Models.PolicyValidationResult;
import org.springframework.stereotype.Component;

@Component
public class ValidationGuardrail {
    public void requireCovered(PolicyValidationResult r) {
        if (!r.policyFound() || !r.policyActive() || !r.covered()) {
            throw new CoverageNotEligibleException(
                    "Policy " + r.policyNumber() + " is not eligible under synthetic coverage rules");
        }
    }
}
