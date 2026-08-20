package com.guidewire.fnol.agents;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;import java.math.*;
@Component public class ReserveCalculatorAgent implements FNOLAgent<DamageAssessment,ReserveResult>{ public ReserveResult execute(DamageAssessment d){ BigDecimal f=new BigDecimal("1.15"); return new ReserveResult(d.estimatedDamage(),f,d.estimatedDamage().multiply(f).setScale(0,RoundingMode.HALF_UP)); } }
