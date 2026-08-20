package com.guidewire.fnol.guardrails;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;
@Component public class PIIGuardrail { public void validate(PIIResult r){ if(r.redactedDescription()!=null&&r.redactedDescription().matches(".*\\d{3}-\\d{2}-\\d{4}.*")) throw new IllegalStateException("PII redaction failed"); } }
