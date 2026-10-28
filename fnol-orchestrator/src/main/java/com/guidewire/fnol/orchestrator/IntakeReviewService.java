package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.common.Models.PolicyHistoryContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Human Review Point 1: completeness, suspicious-indicator and policy/claimant checks run after the
 * policy lookup and before the fan-out. Any flag blocks STP and routes the claim to an adjuster.
 */
@Component
public class IntakeReviewService {

    public static final String MISSING_EVIDENCE = "MISSING_EVIDENCE";
    public static final String CLAIMANT_NAME_MISMATCH = "CLAIMANT_NAME_MISMATCH";
    public static final String INCIDENT_OUTSIDE_POLICY_PERIOD = "INCIDENT_OUTSIDE_POLICY_PERIOD";
    public static final String INCIDENT_NEAR_POLICY_START = "INCIDENT_NEAR_POLICY_START";
    public static final String HIGH_CLAIM_FREQUENCY = "HIGH_CLAIM_FREQUENCY";
    /** Photos were sent, but not the standard set: every side of the vehicle plus a close-up of the damage. */
    public static final String INCOMPLETE_PHOTO_SET = "INCOMPLETE_PHOTO_SET";
    /** A photo was already submitted on another claim (same file, or resized/recompressed): a fraud indicator. */
    public static final String PHOTO_REUSED = "PHOTO_REUSED";

    /** Standard photo set for a vehicle claim: all four sides show the extent and any earlier damage. */
    public static final List<String> AUTO_PHOTO_SET = List.of("FRONT", "REAR", "LEFT", "RIGHT", "CLOSE_UP");
    /** Standard photo set for a home claim. */
    public static final List<String> HOME_PHOTO_SET = List.of("OVERVIEW", "CLOSE_UP");

    static final int NEAR_POLICY_START_DAYS = 30;

    public List<String> review(FNOLPayload payload, Policy policy, PolicyHistoryContext history) {
        List<String> flags = new ArrayList<>();

        if (payload.evidenceUris() == null || payload.evidenceUris().stream().allMatch(u -> u == null || u.isBlank())) {
            flags.add(MISSING_EVIDENCE);
        } else if (!missingPhotoAngles(payload, policy).isEmpty()) {
            flags.add(INCOMPLETE_PHOTO_SET);
        }

        if (payload.claimantName() != null && !payload.claimantName().isBlank()
                && !normalize(payload.claimantName()).equals(normalize(policy.insuredName()))) {
            flags.add(CLAIMANT_NAME_MISMATCH);
        }

        LocalDate incident = payload.incidentDate();
        boolean beforeStart = policy.effectiveDate() != null && incident.isBefore(policy.effectiveDate());
        boolean afterEnd = policy.expirationDate() != null && incident.isAfter(policy.expirationDate());
        if (beforeStart || afterEnd) {
            flags.add(INCIDENT_OUTSIDE_POLICY_PERIOD);
        } else if (policy.effectiveDate() != null
                && ChronoUnit.DAYS.between(policy.effectiveDate(), incident) < NEAR_POLICY_START_DAYS) {
            flags.add(INCIDENT_NEAR_POLICY_START);
        }

        if ("HIGH".equalsIgnoreCase(history.claimFrequencyRisk())) {
            flags.add(HIGH_CLAIM_FREQUENCY);
        }
        return List.copyOf(flags);
    }

    /** Angles of the standard set for the policy's line that no photo covers, in set order. */
    public static List<String> missingPhotoAngles(FNOLPayload payload, Policy policy) {
        Set<String> have = new LinkedHashSet<>();
        List<String> uris = payload.evidenceUris() == null ? List.of() : payload.evidenceUris();
        for (int i = 0; i < uris.size(); i++) {
            if (uris.get(i) != null && !uris.get(i).isBlank()) {
                have.add(payload.angleOf(i));
            }
        }
        List<String> required = "HOME".equalsIgnoreCase(policy.policyType()) ? HOME_PHOTO_SET : AUTO_PHOTO_SET;
        return required.stream().filter(a -> !have.contains(a)).toList();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
