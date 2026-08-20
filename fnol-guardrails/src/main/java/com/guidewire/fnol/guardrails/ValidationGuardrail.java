package com.guidewire.fnol.guardrails;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;
@Component public class ValidationGuardrail { public void requireCovered(PolicyValidationResult r){ if(!r.policyFound()||!r.policyActive()||!r.covered()) throw new IllegalStateException("Policy is not eligible under synthetic Sprint 2 coverage rules"); } }
