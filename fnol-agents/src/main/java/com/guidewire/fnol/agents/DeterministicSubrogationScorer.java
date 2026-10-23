package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keyword subrogation score (0-100), computed at the reconciliation gate with Branch A's real
 * damage severity. Weights are prototype values.
 */
@Component
public class DeterministicSubrogationScorer implements SubrogationScorer {

    static final int INVESTIGATE_THRESHOLD = 70;

    @Override
    public SubrogationResult score(FNOLPayload payload, DamageAssessment damage) {
        Map<String, Integer> factors = new LinkedHashMap<>();
        String text = payload.description().toLowerCase();

        factors.put("thirdPartyMention", text.contains("other driver") || text.contains("rear-ended") ? 35 : 10);
        factors.put("policeReportSignal", text.contains("police") ? 20 : 5);
        factors.put("damageSeverity", severityPoints(damage.severity()));

        int score = factors.values().stream().mapToInt(Integer::intValue).sum();
        return new SubrogationResult(Math.min(100, score),
                score >= INVESTIGATE_THRESHOLD ? "INVESTIGATE" : "MONITOR", factors);
    }

    /** MINOR 5, MODERATE 15, SEVERE 25; anything else is treated as MODERATE. */
    static int severityPoints(String severity) {
        if (severity == null) return 15;
        return switch (severity) {
            case "MINOR" -> 5;
            case "SEVERE" -> 25;
            default -> 15;
        };
    }
}
