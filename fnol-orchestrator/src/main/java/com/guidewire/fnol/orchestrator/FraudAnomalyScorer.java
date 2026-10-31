package com.guidewire.fnol.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fraud anomaly score: how unusual a claim's intake features are, as a sum of one-sided robust z-scores against a
 * reference profile fitted by {@code tools/fraud-eval/fraud_eval.py} (parameters in {@code fraud-anomaly.json}).
 *
 * <pre>
 *   continuous feature:  points = clamp(direction * (transform(x) - median) / scale, 0, cap)
 *   binary feature:      points = weight when true
 *   score = sum of points;  FRAUD_ANOMALY when score >= threshold (fnol.stp.fraud-anomaly-threshold, else the file's)
 * </pre>
 *
 * Features: amount claimed / AI estimate, days from policy start to loss, days from loss to report, claims in the
 * last 12 months, the second opinion's disagreement with the AI damage type, and a reused photo. A missing feature
 * adds nothing. Channel, time of day, location and personal attributes are deliberately not used (fairness).
 *
 * <p>The reference profile is fitted on SYNTHETIC data (no labelled fraud data is available); results on real claims
 * are unknown. The flag routes the claim to an adjuster and suggests an SIU referral. It never denies.
 */
@Component
public class FraudAnomalyScorer {

    private static final Logger log = LoggerFactory.getLogger(FraudAnomalyScorer.class);
    public static final String FRAUD_ANOMALY = "FRAUD_ANOMALY";
    static final String RESOURCE = "fraud-anomaly.json";
    static final int TOP = 3;

    record Feature(String name, String label, String type, int direction, String transform, double median,
                   double scale, double weight) {}

    private final List<Feature> features;
    private final double cap;
    private final BigDecimal fileThreshold;
    private final String basis;
    private final StpProperties stp;

    @Autowired
    public FraudAnomalyScorer(StpProperties stp) {
        this(read(), stp);
    }

    FraudAnomalyScorer(JsonNode json, StpProperties stp) {
        this.stp = stp;
        if (json == null) {
            features = List.of();
            cap = 0;
            fileThreshold = null;
            basis = null;
            return;
        }
        List<Feature> fs = new ArrayList<>();
        for (JsonNode f : json.path("features")) {
            fs.add(new Feature(f.path("name").asText(), f.path("label").asText(), f.path("type").asText(),
                    f.path("direction").asInt(1), f.path("transform").asText("none"), f.path("median").asDouble(),
                    f.path("scale").asDouble(1), f.path("weight").asDouble()));
        }
        features = List.copyOf(fs);
        cap = json.path("cap").asDouble(4);
        fileThreshold = json.hasNonNull("threshold") ? BigDecimal.valueOf(json.path("threshold").asDouble()) : null;
        basis = json.path("dataset").asText(null);
        log.info("[Fraud] Anomaly score active: {} features, threshold {} ({})", features.size(), threshold(), basis);
    }

    /** A scorer with no parameters: scores nothing. */
    public static FraudAnomalyScorer none() {
        return new FraudAnomalyScorer(null, new StpProperties());
    }

    public boolean isActive() {
        return !features.isEmpty() && fileThreshold != null && stp.isFraudAnomalyEnabled();
    }

    public BigDecimal threshold() {
        return stp.getFraudAnomalyThreshold() != null ? stp.getFraudAnomalyThreshold() : fileThreshold;
    }

    /** The features available at intake. Values are null when unknown (they then add nothing). */
    public static Map<String, Object> features(FNOLPayload payload, Policy policy, PolicyHistoryContext history,
                                               BranchAResult branchA, boolean photoReused, LocalDate today) {
        Map<String, Object> x = new LinkedHashMap<>();
        BigDecimal estimate = branchA.damageAssessment().estimatedDamage();
        x.put("claimRatio", payload.claimedAmount() != null && estimate != null && estimate.signum() > 0
                && payload.claimedAmount().signum() > 0
                ? payload.claimedAmount().doubleValue() / estimate.doubleValue() : null);
        x.put("policyAgeDays", policy.effectiveDate() == null ? null
                : (double) ChronoUnit.DAYS.between(policy.effectiveDate(), payload.incidentDate()));
        x.put("reportLagDays", (double) ChronoUnit.DAYS.between(payload.incidentDate(), today));
        x.put("priorClaims12m", history == null ? null : (double) history.claimsLast12Months());
        SecondOpinion so = branchA.secondOpinion();
        BigDecimal agreement = so == null ? null : so.probabilityOf(branchA.damageAssessment().damageType());
        x.put("secondOpinionDisagreement", agreement == null ? null : 1 - agreement.doubleValue());
        x.put("photoReused", photoReused);
        return x;
    }

    /** The score with its top contributors, or null when the scorer is off. */
    public FraudAnomaly score(Map<String, Object> x) {
        if (!isActive()) {
            return null;
        }
        double total = 0;
        List<FraudContribution> parts = new ArrayList<>();
        for (Feature f : features) {
            Object v = x.get(f.name());
            if (v == null) {
                continue;
            }
            double points;
            String shown;
            if ("binary".equals(f.type())) {
                boolean on = v instanceof Boolean b ? b : ((Number) v).doubleValue() != 0;
                points = on ? f.weight() : 0;
                shown = on ? "yes" : "no";
            } else {
                double value = ((Number) v).doubleValue();
                double z = f.direction() * (transform(value, f.transform()) - f.median()) / f.scale();
                points = Math.min(Math.max(z, 0), cap);
                shown = display(f.name(), value);
            }
            total += points;
            if (points > 0) {
                parts.add(new FraudContribution(f.name(), f.label(), shown, round(points)));
            }
        }
        parts.sort(Comparator.comparing(FraudContribution::points).reversed());
        BigDecimal score = round(total);
        BigDecimal threshold = threshold();
        return new FraudAnomaly(score, threshold, score.compareTo(threshold) >= 0,
                List.copyOf(parts.subList(0, Math.min(TOP, parts.size()))), basis);
    }

    static double transform(double x, String how) {
        return switch (how) {
            case "log" -> Math.log(x);
            case "log1p" -> Math.log1p(Math.max(x, 0));
            default -> x;
        };
    }

    private static String display(String name, double v) {
        return switch (name) {
            case "claimRatio" -> String.format("%.2f x", v);
            case "secondOpinionDisagreement" -> String.format("%.0f%%", v * 100);
            default -> String.valueOf(Math.round(v));
        };
    }

    private static BigDecimal round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static JsonNode read() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            log.info("[Fraud] No {} on the classpath; the fraud anomaly score is off.", RESOURCE);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            log.warn("[Fraud] {} could not be read; the fraud anomaly score is off: {}", RESOURCE, e.getMessage());
            return null;
        }
    }
}
