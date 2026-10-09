
package com.guidewire.fnol.guardrails;

import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ComplianceBlockException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PIIGuardrail {

    public void validate(PIIResult r) {

        if (r.redactionConfidence() == null ||
                r.redactionConfidence().compareTo(
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