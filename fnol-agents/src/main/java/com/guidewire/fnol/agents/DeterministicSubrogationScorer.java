package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keyword subrogation score (0-100). Liability (third party, police report) is assessed in
 * Branch B; the damage-severity points are added at the reconciliation gate. Weights and
 * compliance notes are prototype values, not legal guidance.
 */
@Component
public class DeterministicSubrogationScorer implements SubrogationScorer {

    static final int INVESTIGATE_THRESHOLD = 70;
    static final String PENDING_SEVERITY = "PENDING_SEVERITY";

    @Override
    public SubrogationResult assessLiability(FNOLPayload payload) {
        String text = payload.description().toLowerCase();
        boolean thirdParty = text.contains("other driver") || text.contains("rear-ended");
        boolean policeReport = text.contains("police");

        Map<String, Integer> factors = new LinkedHashMap<>();
        factors.put("thirdPartyMention", thirdParty ? 35 : 10);
        factors.put("policeReportSignal", policeReport ? 20 : 5);

        List<String> notes = new ArrayList<>();
        if (thirdParty) {
            notes.add("Third-party involvement indicated: preserve evidence and identify the other party's insurer "
                    + "before any payment.");
            notes.add(policeReport
                    ? "Police report referenced: record the report number in the liability file."
                    : "No police report referenced: request one to support a liability claim.");
        } else {
            notes.add("No third party identified in the description: subrogation recovery is unlikely.");
        }

        int partial = factors.values().stream().mapToInt(Integer::intValue).sum();
        return new SubrogationResult(partial, PENDING_SEVERITY, factors, List.copyOf(notes));
    }

    @Override
    public SubrogationResult applySeverity(SubrogationResult liability, DamageAssessment damage) {
        Map<String, Integer> factors = new LinkedHashMap<>(liability.factors());
        factors.put("damageSeverity", severityPoints(damage.severity()));

        int score = Math.min(100, factors.values().stream().mapToInt(Integer::intValue).sum());
        boolean investigate = score >= INVESTIGATE_THRESHOLD;

        List<String> notes = new ArrayList<>(liability.complianceNotes());
        notes.add(investigate
                ? "Score " + score + " >= " + INVESTIGATE_THRESHOLD + ": refer to the subrogation unit. Recovery "
                        + "deadlines vary by state (prototype placeholder; confirm with Compliance)."
                : "Score " + score + " < " + INVESTIGATE_THRESHOLD + ": monitor; no subrogation referral recommended.");

        return new SubrogationResult(score, investigate ? "INVESTIGATE" : "MONITOR", factors, List.copyOf(notes));
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
