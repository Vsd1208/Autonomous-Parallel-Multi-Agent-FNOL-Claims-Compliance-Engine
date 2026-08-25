package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class DeterministicSubrogationScorer implements SubrogationScorer {
    @Override
    public SubrogationResult score(FNOLPayload payload, DamageAssessment damage) {
        Map<String, Integer> factors = new LinkedHashMap<>();
        String text = payload.description().toLowerCase();

        factors.put("thirdPartyMention", text.contains("other driver") || text.contains("rear-ended") ? 35 : 10);
        factors.put("policeReportSignal", text.contains("police") ? 20 : 5);
        factors.put("damageSeverity", damage.severity().equals("MODERATE") ? 23 : 10);

        int score = factors.values().stream().mapToInt(Integer::intValue).sum();
        return new SubrogationResult(Math.min(100, score), score >= 70 ? "INVESTIGATE" : "MONITOR", factors);
    }
}
