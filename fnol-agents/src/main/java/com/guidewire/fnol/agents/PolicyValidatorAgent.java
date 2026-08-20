package com.guidewire.fnol.agents;
import com.guidewire.fnol.common.Models.*;import com.guidewire.fnol.enrichment.*;import org.springframework.stereotype.*;import java.math.*;import java.time.*;import java.util.*;
@Component public class PolicyValidatorAgent implements FNOLAgent<String,PolicyValidationResult>{
  private final PolicyCenterClient pc; public PolicyValidatorAgent(PolicyCenterClient pc){this.pc=pc;}
  public PolicyValidationResult execute(String n){ Policy p=pc.getPolicy(n); var cs=pc.getCoverages(n); var c=cs.stream().filter(x->x.type().equals("COLLISION")).findFirst(); boolean active="ACTIVE".equals(p.status())&&!LocalDate.now().isAfter(p.expirationDate()); return new PolicyValidationResult(n,true,active,c.isPresent(),active&&c.isPresent(),c.map(Coverage::type).orElse(null),c.map(Coverage::limit).orElse(BigDecimal.ZERO),c.map(Coverage::deductible).orElse(BigDecimal.ZERO),List.of("Mock PolicyCenter policy found","Policy status and effective dates evaluated","Collision coverage checked using synthetic Sprint 2 rule")); }
}
