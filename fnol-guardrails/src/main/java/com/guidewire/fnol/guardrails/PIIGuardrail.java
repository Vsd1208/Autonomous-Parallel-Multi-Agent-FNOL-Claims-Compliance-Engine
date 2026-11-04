
package com.guidewire.fnol.guardrails;

import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ComplianceBlockException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PIIGuardrail {

    public void validate(PIIResult r) {

        if (PIIResult.VALIDATION_FAILED.equals(r.validation())) {
            throw new ComplianceBlockException("PII redaction failed: personal data was still found after masking");
        }

        // A rule-based result has no confidence; its re-scan above is the check. A detector reporting a confidence is held to the threshold.
        if (r.validation() == null && r.redactionConfidence() == null ||
                r.redactionConfidence() != null && r.redactionConfidence().compareTo(
                        new BigDecimal("0.90")) < 0) {
            throw new ComplianceBlockException(
                    "PII redaction confidence is below 0.90");
        }

        if (r.redactedDescription() != null &&
                r.redactedDescription().matches(
                        ".*\\d{3}-\\d{2}-\\d{4}.*")) {
            throw new ComplianceBlockException(
                    "PII redaction failed: raw SSN detected");
        }

        if (r.detectedTypes() != null && r.detectedTypes().contains("MRN")) {
            throw new ComplianceBlockException(
                    "Medical Record Number (MRN) detected. Compliance violation.");
        }
    }
}