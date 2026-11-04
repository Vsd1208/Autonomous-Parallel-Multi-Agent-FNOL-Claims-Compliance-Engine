package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.OnPremiseVision;
import com.guidewire.fnol.common.Models.Abstention;
import com.guidewire.fnol.common.Models.DamageAssessment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Says when the AI has no answer worth relying on, and why. An abstained claim goes to a person; abstaining is never a rejection and
 * says nothing against the claim. {@code OUT_OF_DISTRIBUTION} means the photos are not of what this line of business expects: no
 * statistical out-of-distribution detector exists, so a stranger photo of the expected kind is not caught here.
 */
final class AbstentionPolicy {

    static final String INSUFFICIENT_QUALITY = "INSUFFICIENT_QUALITY";
    static final String MODEL_DISAGREEMENT = "MODEL_DISAGREEMENT";
    static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    static final String UNSUPPORTED_DAMAGE = "UNSUPPORTED_DAMAGE";
    static final String OUT_OF_DISTRIBUTION = "OUT_OF_DISTRIBUTION";
    static final String INFERENCE_FAILURE = "INFERENCE_FAILURE";
    static final String MISSING_MODEL_VERSION = "MISSING_MODEL_VERSION";
    static final String MODEL_VERSION_CHANGED = "MODEL_VERSION_CHANGED";

    /** The gate flag that sends an abstained claim to a person when nothing else did. */
    static final String AI_ABSTAINED = "AI_ABSTAINED";

    private static final Set<String> UNREADABLE_PHOTOS = Set.of("BLURRY", "UNUSABLE");

    private AbstentionPolicy() {
    }

    static Abstention evaluate(DamageAssessment d, Collection<String> flags, BigDecimal minimumConfidence, boolean requireModelVersion) {
        List<String> reasons = new ArrayList<>();
        boolean noAnswer = "UNKNOWN".equals(d.damageType());
        if ((noAnswer && d.visionSource() == null) || OnPremiseVision.isOnPremise(d) || flags.contains(GateConsistencyChecker.ON_PREMISE_VISION)) {
            reasons.add(INFERENCE_FAILURE);
        } else if (noAnswer || flags.contains(GateConsistencyChecker.DAMAGE_TYPE_LINE_MISMATCH)) {
            reasons.add(UNSUPPORTED_DAMAGE);
        }
        if (d.photoQuality() != null && UNREADABLE_PHOTOS.contains(d.photoQuality())) {
            reasons.add(INSUFFICIENT_QUALITY);
        }
        if (flags.contains(GateConsistencyChecker.MODEL_DISAGREEMENT)) {
            reasons.add(MODEL_DISAGREEMENT);
        }
        if (flags.contains(GateConsistencyChecker.AMBIGUOUS_DAMAGE_TYPE)
                || d.confidence() == null || d.confidence().compareTo(minimumConfidence) < 0) {
            reasons.add(LOW_CONFIDENCE);
        }
        if (flags.contains(GateConsistencyChecker.PHOTO_SUBJECT_MISMATCH)) {
            reasons.add(OUT_OF_DISTRIBUTION);
        }
        if (requireModelVersion && d.visionSource() == null) {
            reasons.add(MISSING_MODEL_VERSION);
        }
        if (d.servedBySubstituteModel()) {
            reasons.add(MODEL_VERSION_CHANGED);
        }
        return new Abstention(!reasons.isEmpty(), List.copyOf(reasons));
    }
}
