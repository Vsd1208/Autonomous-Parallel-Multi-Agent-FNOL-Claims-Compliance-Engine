package com.guidewire.fnol.guardrails;
import com.guidewire.fnol.common.Models.*;import org.springframework.stereotype.*;import java.math.*;
@Component public class OutputGuardrail { public void validate(ReserveResult r, SubrogationResult s){ if(r.recommendedReserve().compareTo(BigDecimal.ZERO)<0) throw new IllegalStateException("Reserve cannot be negative"); if(s.score()<0||s.score()>100) throw new IllegalStateException("Subrogation score outside 0-100"); } }
