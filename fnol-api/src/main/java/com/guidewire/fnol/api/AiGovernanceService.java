package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.AiFeedbackEntity;
import com.guidewire.fnol.api.persistence.AiFeedbackRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI governance: records each adjuster decision next to what the AI said (the feedback loop), and computes the
 * figures a claims manager or model-risk reviewer needs: how claims were routed and why, money the reserve rule kept
 * from being over-paid, how often adjusters agree with the AI, what left the system for the external model, and
 * whether the AI's answers drift over time.
 */
@Service
public class AiGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(AiGovernanceService.class);
    static final Set<String> STP_ELIGIBLE = Set.of("PENDING_ADVISORY_REVIEW", "STRAIGHT_THROUGH_PROCESSING");
    static final Set<String> HEDGED = Set.of("MULTIPLE", "UNKNOWN");

    private final AiFeedbackRepository feedback;
    private final ClaimRepository claims;
    private final ObjectMapper mapper;

    public AiGovernanceService(AiFeedbackRepository feedback, ClaimRepository claims, ObjectMapper mapper) {
        this.feedback = feedback;
        this.claims = claims;
        this.mapper = mapper;
    }

    /** The AI's view of a claim, read from the analysis stored at intake. */
    record AiView(String line, String damageType, String status, BigDecimal reserve, BigDecimal confidence,
                  BigDecimal rawConfidence, BigDecimal margin, BigDecimal agreement, String subject, JsonNode privacy,
                  String visionSource) {}

    AiView view(ClaimEntity claim) {
        JsonNode a = parse(claim.analysisJson);
        JsonNode d = a.path("branchA").path("damageAssessment");
        String coverage = a.path("branchA").path("coverage").path("coverageType").asText("");
        String line = coverage.equals("DWELLING") || coverage.equals("PERSONAL_PROPERTY")
                || (claim.policyNumber != null && claim.policyNumber.startsWith("POL-HOME")) ? "HOME" : "AUTO";
        String type = text(d, "damageType");
        BigDecimal agreement = type == null ? null
                : decimal(a.path("branchA").path("secondOpinion").path("damageProbabilities").path(type));
        return new AiView(line, type, text(a, "status"), claim.reserve, decimal(d.path("confidence")),
                decimal(d.path("rawConfidence")), margin(d.path("damageTypeProbabilities")), agreement,
                text(d, "photoSubject"), d.path("privacy"), text(d, "visionSource"));
    }

    /** Records an adjuster decision next to the AI's view. Never throws: the decision itself is already saved. */
    public void recordDecision(ClaimEntity claim, String decision, String adjusterId, BigDecimal adjusterReserve,
                               String confirmedDamageType) {
        try {
            AiView ai = view(claim);
            AiFeedbackEntity f = new AiFeedbackEntity();
            f.claimId = claim.claimId;
            f.lineOfBusiness = ai.line();
            f.aiDamageType = ai.damageType();
            f.adjusterDamageType = blankToNull(confirmedDamageType);
            f.aiStatus = ai.status();
            f.adjusterDecision = decision;
            f.aiReserve = claim.reserve;
            f.adjusterReserve = adjusterReserve != null ? adjusterReserve : claim.reserve;
            f.aiConfidence = ai.confidence();
            f.rawConfidence = ai.rawConfidence();
            f.probabilityMargin = ai.margin();
            f.secondOpinionAgreement = ai.agreement();
            f.photoSubject = ai.subject();
            f.visionSource = ai.visionSource();
            f.gateFlags = claim.gateFlags;
            f.adjusterId = adjusterId;
            f.decidedAt = Instant.now();
            feedback.save(f);
        } catch (RuntimeException e) {
            log.warn("[AI governance] Feedback not recorded for claimId={}: {}", claim.claimId, e.getMessage());
        }
    }

    public List<AiFeedbackEntity> feedback() {
        return feedback.findAllByOrderByDecidedAtAsc();
    }

    /** Dashboard figures over all filed claims (REJ- intake failures excluded) and all recorded decisions. */
    public Map<String, Object> metrics() {
        List<ClaimEntity> filed = claims.findAll().stream()
                .filter(c -> c.claimId != null && !c.claimId.startsWith("REJ-")).toList();
        List<AiFeedbackEntity> decisions = feedback();
        Map<String, Object> out = new LinkedHashMap<>();

        // Routing: what the AI did with each claim at intake, and why claims went to a person.
        Map<String, AiView> views = new LinkedHashMap<>();
        for (ClaimEntity c : filed) views.put(c.claimId, view(c));
        long withAnalysis = views.values().stream().filter(v -> v.status() != null).count();
        long stp = views.values().stream().filter(v -> v.status() != null && STP_ELIGIBLE.contains(v.status())).count();
        out.put("claims", Map.of("filed", filed.size(), "withAiAnalysis", withAnalysis, "straightThroughEligible", stp,
                "sentToAdjuster", withAnalysis - stp));
        out.put("flags", countFlags(filed));

        // Leakage: what claimants asked for above what the photos support, which the reserve rule never pays.
        BigDecimal askedAbove = BigDecimal.ZERO;
        long claimsAbove = 0;
        for (ClaimEntity c : filed) {
            if (c.claimedAmount != null && c.estimatedDamage != null && c.claimedAmount.compareTo(c.estimatedDamage) > 0) {
                askedAbove = askedAbove.add(c.claimedAmount.subtract(c.estimatedDamage));
                claimsAbove++;
            }
        }
        out.put("leakage", Map.of("claimsAskingAboveEstimate", claimsAbove, "amountNotReserved", askedAbove));

        // Agreement: how adjusters' decisions compare with the AI.
        long confirmed = decisions.stream().filter(f -> f.adjusterDamageType != null && f.aiDamageType != null).count();
        long sameType = decisions.stream().filter(f -> f.adjusterDamageType != null && f.adjusterDamageType.equals(f.aiDamageType)).count();
        long stpDecided = decisions.stream().filter(f -> f.aiStatus != null && STP_ELIGIBLE.contains(f.aiStatus)).count();
        long stpApproved = decisions.stream().filter(f -> f.aiStatus != null && STP_ELIGIBLE.contains(f.aiStatus)
                && "APPROVED_BY_ADJUSTER".equals(f.adjusterDecision)).count();
        List<AiFeedbackEntity> withReserves = decisions.stream()
                .filter(f -> f.aiReserve != null && f.adjusterReserve != null).toList();
        long reserveChanged = withReserves.stream().filter(f -> f.aiReserve.compareTo(f.adjusterReserve) != 0).count();
        BigDecimal meanChange = withReserves.isEmpty() ? BigDecimal.ZERO : withReserves.stream()
                .map(f -> f.adjusterReserve.subtract(f.aiReserve).abs()).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(withReserves.size()), 2, RoundingMode.HALF_UP);
        Map<String, Object> agreement = new LinkedHashMap<>();
        agreement.put("decisions", decisions.size());
        agreement.put("damageTypeConfirmed", confirmed);
        agreement.put("damageTypeAgreed", sameType);
        agreement.put("straightThroughEligibleDecided", stpDecided);
        agreement.put("straightThroughEligibleApproved", stpApproved);
        agreement.put("reserveChanged", reserveChanged);
        agreement.put("meanReserveChange", meanChange);
        agreement.put("byDecision", decisions.stream().collect(Collectors.groupingBy(f -> f.adjusterDecision,
                TreeMap::new, Collectors.counting())));
        out.put("agreement", agreement);

        // Privacy: what was sent to the external model.
        int sent = 0, withheld = 0, faces = 0, plates = 0, redactedClaims = 0;
        for (AiView v : views.values()) {
            if (v.privacy() == null || v.privacy().isMissingNode() || v.privacy().isNull()) continue;
            sent += v.privacy().path("photosSent").asInt();
            withheld += v.privacy().path("photosWithheld").asInt();
            faces += v.privacy().path("facesBlurred").asInt();
            plates += v.privacy().path("platesBlurred").asInt();
            if (v.privacy().path("redacted").asBoolean()) redactedClaims++;
        }
        out.put("privacy", Map.of("photosSent", sent, "photosWithheld", withheld, "facesBlurred", faces,
                "platesBlurred", plates, "claimsRedacted", redactedClaims));

        // Drift: per ISO week, how sure and how consistent the AI's answers were.
        Map<String, List<AiView>> byWeek = new TreeMap<>();
        for (ClaimEntity c : filed) {
            AiView v = views.get(c.claimId);
            if (v.damageType() == null || c.createdAt == null) continue;
            var day = c.createdAt.atZone(ZoneOffset.UTC);
            String week = day.get(IsoFields.WEEK_BASED_YEAR) + "-W%02d".formatted(day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            byWeek.computeIfAbsent(week, k -> new ArrayList<>()).add(v);
        }
        List<Map<String, Object>> drift = new ArrayList<>();
        byWeek.forEach((week, vs) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week", week);
            row.put("claims", vs.size());
            row.put("meanMargin", mean(vs.stream().map(AiView::margin).toList()));
            row.put("meanConfidence", mean(vs.stream().map(AiView::confidence).toList()));
            row.put("hedgedShare", share(vs, v -> HEDGED.contains(v.damageType())));
            row.put("secondOpinionLowShare", share(vs.stream().filter(v -> v.agreement() != null).toList(),
                    v -> v.agreement().compareTo(new BigDecimal("0.15")) < 0));
            drift.add(row);
        });
        out.put("drift", drift);
        out.put("deadlines", deadlineCompliance(filed));
        out.put("fairness", fairness(filed, views, decisions));
        return out;
    }

    /** Groups smaller than this are reported but not compared: their rates are too noisy. */
    static final int MIN_GROUP = 5;
    /** "Four-fifths rule": a group whose rate is below 80% of the best group's is flagged for review. */
    static final BigDecimal DISPARITY_THRESHOLD = new BigDecimal("0.80");

    /**
     * Unfair-discrimination testing of the AI's outcomes by group (state, reporting channel, line of business): how
     * often claims went straight through, how often adjusters approved them, adjusters' reserve changes, and reserve as
     * a share of the amount claimed. Each rate is compared with the best group's (disparity ratio); below 0.80 is
     * flagged for review. The engine holds no protected attributes (and should not), so this tests proxies only.
     */
    Map<String, Object> fairness(List<ClaimEntity> filed, Map<String, AiView> views, List<AiFeedbackEntity> decisions) {
        Map<String, List<AiFeedbackEntity>> decisionsByClaim = decisions.stream()
                .collect(Collectors.groupingBy(f -> f.claimId));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, java.util.function.Function<ClaimEntity, String>> dimensions = new LinkedHashMap<>();
        dimensions.put("state", c -> c.state == null ? "—" : c.state);
        dimensions.put("channel", c -> c.channel == null ? "—" : c.channel);
        dimensions.put("lineOfBusiness", c -> views.get(c.claimId).line());
        dimensions.forEach((dimension, key) -> {
            Map<String, List<ClaimEntity>> groups = filed.stream()
                    .filter(c -> views.get(c.claimId).status() != null)
                    .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
            List<Map<String, Object>> rows = new ArrayList<>();
            groups.forEach((group, cs) -> {
                long stp = cs.stream().filter(c -> STP_ELIGIBLE.contains(views.get(c.claimId).status())).count();
                List<AiFeedbackEntity> ds = cs.stream().flatMap(c -> decisionsByClaim.getOrDefault(c.claimId, List.of()).stream()).toList();
                long approved = ds.stream().filter(f -> "APPROVED_BY_ADJUSTER".equals(f.adjusterDecision)).count();
                long changed = ds.stream().filter(f -> f.aiReserve != null && f.adjusterReserve != null
                        && f.aiReserve.compareTo(f.adjusterReserve) != 0).count();
                List<BigDecimal> ratios = cs.stream()
                        .filter(c -> c.claimedAmount != null && c.claimedAmount.signum() > 0 && c.reserve != null)
                        .map(c -> c.reserve.divide(c.claimedAmount, 4, RoundingMode.HALF_UP)).toList();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("group", group);
                row.put("claims", cs.size());
                row.put("straightThroughRate", rate(stp, cs.size()));
                row.put("decisions", ds.size());
                row.put("approvalRate", rate(approved, ds.size()));
                row.put("reserveChangedRate", rate(changed, ds.size()));
                row.put("reserveToClaimed", mean(ratios));
                rows.add(row);
            });
            addDisparity(rows, "straightThroughRate", "claims");
            addDisparity(rows, "approvalRate", "decisions");
            out.put(dimension, rows);
        });
        out.put("method", "Disparity ratio = a group's rate / the highest rate among groups with at least " + MIN_GROUP
                + " cases; below " + DISPARITY_THRESHOLD + " is flagged REVIEW (four-fifths rule). Proxies only: no "
                + "protected attributes are collected.");
        return out;
    }

    private static void addDisparity(List<Map<String, Object>> rows, String rateKey, String countKey) {
        BigDecimal best = rows.stream()
                .filter(r -> ((Number) r.get(countKey)).intValue() >= MIN_GROUP && r.get(rateKey) != null)
                .map(r -> (BigDecimal) r.get(rateKey)).max(Comparator.naturalOrder()).orElse(null);
        String prefix = rateKey.replace("Rate", "");
        for (Map<String, Object> r : rows) {
            BigDecimal rate = (BigDecimal) r.get(rateKey);
            if (((Number) r.get(countKey)).intValue() < MIN_GROUP || rate == null || best == null) {
                r.put(prefix + "Disparity", null);
                r.put(prefix + "Status", "TOO_FEW");
            } else if (best.signum() == 0) {
                r.put(prefix + "Disparity", BigDecimal.ONE);
                r.put(prefix + "Status", "OK");
            } else {
                BigDecimal ratio = rate.divide(best, 2, RoundingMode.HALF_UP);
                r.put(prefix + "Disparity", ratio);
                r.put(prefix + "Status", ratio.compareTo(DISPARITY_THRESHOLD) < 0 ? "REVIEW" : "OK");
            }
        }
    }

    private static BigDecimal rate(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }

    /** Written acknowledgement filed on time, and open claims near or past their decision deadline. */
    Map<String, Object> deadlineCompliance(List<ClaimEntity> filed) {
        java.time.LocalDate today = java.time.LocalDate.now(ZoneOffset.UTC);
        long withRule = 0, onTime = 0, late = 0, missing = 0, noRule = 0, overdue = 0, dueSoon = 0, clockNotStarted = 0;
        for (ClaimEntity c : filed) {
            if (c.acknowledgeDue == null) {
                noRule++;
            } else {
                withRule++;
                if (c.acknowledgedAt == null) missing++;
                else if (!c.acknowledgedAt.atZone(ZoneOffset.UTC).toLocalDate().isAfter(c.acknowledgeDue)) onTime++;
                else late++;
            }
            boolean open = FNOLController.REVIEWABLE_STATUSES.contains(c.status);
            if (open && c.decisionDue != null) {
                if (today.isAfter(c.decisionDue)) overdue++;
                else if (!today.plusDays(3).isBefore(c.decisionDue)) dueSoon++;
            } else if (open && c.acknowledgeDue != null) {
                clockNotStarted++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claimsWithRule", withRule);
        out.put("acknowledgedOnTime", onTime);
        out.put("acknowledgedLate", late);
        out.put("notAcknowledged", missing);
        out.put("claimsWithoutRule", noRule);
        out.put("openDecisionsOverdue", overdue);
        out.put("openDecisionsDueWithin3Days", dueSoon);
        out.put("openWithoutDecisionClock", clockNotStarted);
        return out;
    }

    private static Map<String, Long> countFlags(List<ClaimEntity> filed) {
        Map<String, Long> counts = new TreeMap<>();
        for (ClaimEntity c : filed) {
            for (String list : new String[]{c.inputFlags, c.gateFlags}) {
                if (list == null || list.isBlank()) continue;
                for (String flag : list.replaceAll("[\\[\\]\" ]", "").split(",")) {
                    if (!flag.isBlank()) counts.merge(flag, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    private JsonNode parse(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static BigDecimal decimal(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.decimalValue();
    }

    private static BigDecimal margin(JsonNode probabilities) {
        List<BigDecimal> values = new ArrayList<>();
        probabilities.forEach(p -> { if (p.isNumber()) values.add(p.decimalValue()); });
        if (values.isEmpty()) return null;
        values.sort(Comparator.reverseOrder());
        return values.get(0).subtract(values.size() > 1 ? values.get(1) : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        List<BigDecimal> present = values.stream().filter(Objects::nonNull).toList();
        return present.isEmpty() ? null : present.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(present.size()), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(List<AiView> vs, java.util.function.Predicate<AiView> p) {
        return vs.isEmpty() ? null : BigDecimal.valueOf(vs.stream().filter(p).count())
                .divide(BigDecimal.valueOf(vs.size()), 2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
