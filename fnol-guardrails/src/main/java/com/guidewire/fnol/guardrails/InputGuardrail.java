package com.guidewire.fnol.guardrails;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;import java.time.*;import java.util.*;import java.util.regex.*;
@Component public class InputGuardrail {
  private static final Pattern POLICY=Pattern.compile("POL-[A-Z]+-\\d{6}"); private static final Set<String> STATES=Set.of("CA","NY","TX","FL","IL");
  public void validate(FNOLPayload p){ if(p.policyNumber()==null||!POLICY.matcher(p.policyNumber()).matches()) throw new IllegalArgumentException("Policy number must match POL-TYPE-123456"); if(p.incidentDate()==null||p.incidentDate().isAfter(LocalDate.now())) throw new IllegalArgumentException("Incident date is required and cannot be in the future"); if(!STATES.contains(p.state())) throw new IllegalArgumentException("State must be one of CA, NY, TX, FL, IL"); if(p.description()==null||p.description().isBlank()) throw new IllegalArgumentException("Description is required"); }
}
