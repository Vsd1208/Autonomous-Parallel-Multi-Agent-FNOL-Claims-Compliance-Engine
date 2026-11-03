package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recommends the initial reserve, the way insurers commonly reserve at first notice: from a table by line of business,
 * coverage and severity ({@code fnol.reserve.method=AVERAGE_TABLE}, the default), because Gemini's dollar figures are
 * unreliable (docs/metrics.md section 8). The AI can only move the base within a band, and only when the trust score passes
 * and no vision check objects:
 * <pre>
 *   supported = table row, moved by the AI within +/- fnol.reserve.ai-adjustment-max when allowed
 *   basis     = min(claimed amount, supported)      (supported when no amount was claimed)
 *   reserve   = min(max(basis − deductible, 0), coverage limit)
 * </pre>
 * {@code AI_ESTIMATE} keeps the old formula, with the AI's figure as "supported", so the two can be compared. A claimed
 * amount above the supported figure is not reserved automatically: the gate flags it (CLAIMED_EXCEEDS_AI_ESTIMATE). A claim
 * with no table row keeps the AI's figure, is reported as missing, and the gate sends it to a person; it is never a silent
 * zero. The confidence score is the vision confidence weighted by how usable the photo was (prototype weights).
 */
@Component
public class ReserveCalculatorAgent implements FNOLAgent<DamageAssessment, ReserveResult> {

    public static final String AVERAGE_TABLE = "AVERAGE_TABLE";
    public static final String AI_ESTIMATE = "AI_ESTIMATE";

    /** Whether the AI may move the base reserve, why, and its figure when it may. */
    public record Adjustment(boolean allowed, BigDecimal signal, String reason) {
        public static Adjustment allowed(BigDecimal signal, String reason) {
            return new Adjustment(true, signal, reason);
        }

        public static Adjustment blocked(String reason) {
            return new Adjustment(false, null, reason);
        }
    }

    private final InitialReserveProvider table;
    private final String method;
    private final BigDecimal adjustmentMax;

    /** The old formula, for callers with no table. */
    public ReserveCalculatorAgent() {
        this(null, AI_ESTIMATE, BigDecimal.ZERO);
    }

    @Autowired
    public ReserveCalculatorAgent(InitialReserveProvider table,
                                  @Value("${fnol.reserve.method:AVERAGE_TABLE}") String method,
                                  @Value("${fnol.reserve.ai-adjustment-max:0.40}") BigDecimal adjustmentMax) {
        if (!AVERAGE_TABLE.equals(method) && !AI_ESTIMATE.equals(method)) {
            throw new IllegalArgumentException("fnol.reserve.method must be " + AVERAGE_TABLE + " or " + AI_ESTIMATE);
        }
        this.table = table;
        this.method = method;
        this.adjustmentMax = adjustmentMax;
    }

    public boolean usesTable() {
        return AVERAGE_TABLE.equals(method) && table != null;
    }

    /** No loading: the reserve is the amount owed under the policy. */
    static final BigDecimal FACTOR = BigDecimal.ONE;

    private static final Map<String, BigDecimal> PHOTO_QUALITY_WEIGHT = Map.of(
            "CLEAR", new BigDecimal("1.00"),
            "PARTIAL", new BigDecimal("0.85"),
            "BLURRY", new BigDecimal("0.70"),
            "UNUSABLE", new BigDecimal("0.50"));
    private static final BigDecimal UNKNOWN_QUALITY_WEIGHT = new BigDecimal("0.80");

    /** Reserve from the AI estimate alone (no claimed amount, no policy terms). */
    @Override
    public ReserveResult execute(DamageAssessment d) {
        return calculate(d, null, null);
    }

    public ReserveResult calculate(DamageAssessment d, PolicyValidationResult coverage, BigDecimal claimedAmount) {
        return result(d, coverage, claimedAmount, d.estimatedDamage(), new ReserveBreakdown(AI_ESTIMATE, false, null, null,
                null, null, false, "The AI's dollar estimate is used directly (fnol.reserve.method=AI_ESTIMATE)", null,
                d.estimatedDamage(), List.of()));
    }

    /** The reserve by the configured method. */
    public ReserveResult reserve(DamageAssessment d, PolicyValidationResult coverage, BigDecimal claimedAmount,
                                 String lineOfBusiness, Adjustment adjustment) {
        if (!usesTable()) {
            return calculate(d, coverage, claimedAmount);
        }
        Optional<InitialReserveProvider.InitialReserve> row = coverage == null ? Optional.empty()
                : table.lookup(lineOfBusiness, coverage.coverageType(), d.severity());
        if (row.isEmpty()) {
            String asked = lineOfBusiness + " / " + (coverage == null ? null : coverage.coverageType()) + " / " + d.severity();
            return result(d, coverage, claimedAmount, d.estimatedDamage(), new ReserveBreakdown(AVERAGE_TABLE, true, asked, null,
                    null, null, false, "No table row for this claim: the AI's figure is shown, and an adjuster must set the reserve",
                    adjustmentMax, d.estimatedDamage(), List.of()));
        }
        InitialReserveProvider.InitialReserve base = row.get();
        BigDecimal supported = base.amount();
        if (adjustment.allowed() && adjustment.signal() != null) {
            BigDecimal low = base.amount().multiply(BigDecimal.ONE.subtract(adjustmentMax));
            BigDecimal high = base.amount().multiply(BigDecimal.ONE.add(adjustmentMax));
            supported = adjustment.signal().max(low).min(high);
        }
        return result(d, coverage, claimedAmount, supported, new ReserveBreakdown(AVERAGE_TABLE, false, base.row(), base.amount(),
                base.provenance(), base.source(), adjustment.allowed(), adjustment.reason(), adjustmentMax, supported, List.of()));
    }

    private ReserveResult result(DamageAssessment d, PolicyValidationResult coverage, BigDecimal claimedAmount,
                                 BigDecimal supported, ReserveBreakdown steps) {
        BigDecimal estimate = d.estimatedDamage();
        List<String> caps = new ArrayList<>();
        BigDecimal basis = supported;
        if (claimedAmount != null && claimedAmount.compareTo(supported) < 0) {
            basis = claimedAmount;
            caps.add("Capped at the amount claimed, $" + claimedAmount);
        }
        BigDecimal deductible = coverage == null || coverage.deductible() == null ? BigDecimal.ZERO : coverage.deductible();
        BigDecimal limit = coverage == null ? null : coverage.coverageLimit();
        if (deductible.signum() > 0) {
            caps.add("Less the deductible, $" + deductible);
        }
        BigDecimal owed = basis.subtract(deductible);
        if (owed.signum() < 0) {
            caps.add("Held at zero: the loss is below the deductible");
            owed = BigDecimal.ZERO;
        }
        if (limit != null && limit.signum() > 0 && owed.compareTo(limit) > 0) {
            caps.add("Capped at the coverage limit, $" + limit);
            owed = limit;
        }
        BigDecimal reserve = owed.multiply(FACTOR).setScale(0, RoundingMode.HALF_UP);
        ReserveBreakdown breakdown = new ReserveBreakdown(steps.method(), steps.tableEntryMissing(), steps.baseRow(),
                steps.baseReserve(), steps.baseProvenance(), steps.baseSource(), steps.adjustmentAllowed(),
                steps.adjustmentReason(), steps.adjustmentMax(), steps.supportedAmount(), List.copyOf(caps));

        String quality = d.photoQuality() == null ? "UNKNOWN" : d.photoQuality();
        BigDecimal weight = PHOTO_QUALITY_WEIGHT.getOrDefault(quality, UNKNOWN_QUALITY_WEIGHT);
        BigDecimal visionConfidence = d.confidence() == null ? BigDecimal.ZERO : d.confidence();
        BigDecimal confidence = visionConfidence.multiply(weight).min(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP);

        return new ReserveResult(estimate, FACTOR, reserve, confidence,
                "vision confidence " + visionConfidence + " × photo quality " + quality + " weight " + weight,
                claimedAmount, basis, deductible, limit, breakdown);
    }
}
