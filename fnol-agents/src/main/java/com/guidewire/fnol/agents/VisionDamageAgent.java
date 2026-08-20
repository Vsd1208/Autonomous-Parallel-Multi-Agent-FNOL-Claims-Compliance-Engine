package com.guidewire.fnol.agents;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;import java.math.*;
@Component class MockVisionProvider implements VisionProvider { public DamageAssessment assess(FNOLPayload p){ return new DamageAssessment("FRONT_BUMPER","MODERATE",new BigDecimal("4200"),new BigDecimal("0.91")); } }
@Component public class VisionDamageAgent implements FNOLAgent<FNOLPayload,DamageAssessment>{ private final VisionProvider p; public VisionDamageAgent(VisionProvider p){this.p=p;} public DamageAssessment execute(FNOLPayload i){return p.assess(i);} }
