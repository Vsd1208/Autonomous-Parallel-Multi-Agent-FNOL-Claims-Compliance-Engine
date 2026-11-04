package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.agents.CoverageRuleProvider.CoverageRule;
import com.guidewire.fnol.common.Models.Endorsement;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.common.Models.PolicyValidationResult;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a policy covers a loss, judged at the date of loss: a loss in term stays covered when the claim is reported after the policy ended.
 * Status, term, cancellation and coverage are checked separately, and anything the data cannot settle is {@code UNVERIFIED}, which goes to a
 * person rather than being guessed. Deterministic: no AI output can change this. Limits to the contract's real wording are not modelled.
 */
@Component
public class PolicyValidatorAgent implements FNOLAgent<String, PolicyValidationResult> {

    /** Statuses that say nothing against a loss in term: the policy may have run out or been renewed since. */
    private static final Set<String> IN_FORCE_STATUSES = Set.of("ACTIVE", "EXPIRED", "RENEWED");

    private final PolicyCenterClient pc;
    private final CoverageRuleProvider rules;
    private final Clock clock;

    @Autowired
    public PolicyValidatorAgent(PolicyCenterClient pc, CoverageRuleProvider rules) {
        this(pc, rules, Clock.systemDefaultZone());
    }

    public PolicyValidatorAgent(PolicyCenterClient pc, CoverageRuleProvider rules, Clock clock) {
        this.pc = pc;
        this.rules = rules;
        this.clock = clock;
    }

    /** With no date of loss to go on, as of today. Intake always has the date: see {@link #execute(String, LocalDate)}. */
    @Override
    public PolicyValidationResult execute(String policyNumber) {
        return execute(policyNumber, LocalDate.now(clock));
    }

    public PolicyValidationResult execute(String policyNumber, LocalDate dateOfLoss) {
        Policy policy = pc.getPolicy(policyNumber);
        if (policy == null) {
            return new PolicyValidationResult(policyNumber, false, false, false, false, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of("Policy not found in PolicyCenter"), PolicyValidationResult.NOT_IN_FORCE);
        }
        List<String> reasoning = new ArrayList<>();
        reasoning.add("PolicyCenter policy found: line of business " + policy.policyType());

        String inForce = inForceAt(policy, dateOfLoss, reasoning);

        CoverageRule rule = rules.ruleFor(policy.policyType());
        reasoning.add("Coverage rule: " + rule.describe());
        List<Coverage> coverages = applicableCoverages(pc.getCoverages(policyNumber), policy.endorsements(), dateOfLoss, reasoning);
        Optional<Coverage> match = rule.acceptedCoverageTypes().stream()
                .flatMap(type -> coverages.stream().filter(c -> type.equals(c.type())))
                .findFirst();
        reasoning.add(match.map(c -> "Matched coverage " + c.type() + " (limit " + c.limit() + ", deductible " + c.deductible() + ")")
                .orElse("No coverage on the policy satisfies the rule"));

        boolean inTerm = PolicyValidationResult.COVERED.equals(inForce);
        String state = !inTerm ? inForce : match.isPresent() ? PolicyValidationResult.COVERED : PolicyValidationResult.NOT_COVERED;
        return new PolicyValidationResult(policyNumber, true, inTerm, match.isPresent(), inTerm && match.isPresent(),
                match.map(Coverage::type).orElse(null),
                match.map(Coverage::limit).orElse(BigDecimal.ZERO),
                match.map(Coverage::deductible).orElse(BigDecimal.ZERO),
                List.copyOf(reasoning), state);
    }

    /** COVERED when the policy was in force at the loss, NOT_IN_FORCE when it was not, UNVERIFIED when the data cannot say. */
    private static String inForceAt(Policy policy, LocalDate loss, List<String> reasoning) {
        if (loss == null || policy.effectiveDate() == null || policy.expirationDate() == null) {
            reasoning.add("Cannot verify the policy at the date of loss: date of loss " + loss + ", term " + policy.effectiveDate() + " to "
                    + policy.expirationDate() + ". A person must check.");
            return PolicyValidationResult.UNVERIFIED;
        }
        if (loss.isBefore(policy.effectiveDate()) || loss.isAfter(policy.expirationDate())) {
            reasoning.add("The loss on " + loss + " is outside the policy term " + policy.effectiveDate() + " to " + policy.expirationDate());
            return PolicyValidationResult.NOT_IN_FORCE;
        }
        LocalDate cancelled = policy.cancellationDate();
        if (cancelled != null && loss.isAfter(cancelled)) {
            reasoning.add("The policy was cancelled on " + cancelled + ", before the loss on " + loss);
            return PolicyValidationResult.NOT_IN_FORCE;
        }
        if (cancelled != null && loss.equals(cancelled)) {
            reasoning.add("The loss is on the day the policy was cancelled, and the time of neither is known. A person must check.");
            return PolicyValidationResult.UNVERIFIED;
        }
        if (loss.equals(policy.expirationDate())) {
            reasoning.add("The loss is on the policy's last day, and whether the term ends at the start or the end of it is in the contract, which is not held here. A person must check.");
            return PolicyValidationResult.UNVERIFIED;
        }
        if (cancelled == null && !IN_FORCE_STATUSES.contains(policy.status())) {
            reasoning.add("The policy's status is " + policy.status() + " with no cancellation date, so whether it applied at the loss is unknown. A person must check.");
            return PolicyValidationResult.UNVERIFIED;
        }
        reasoning.add("The policy was in force at the loss on " + loss + " (term " + policy.effectiveDate() + " to " + policy.expirationDate() + ")"
                + (cancelled == null ? "" : ", cancelled later on " + cancelled));
        return PolicyValidationResult.COVERED;
    }

    /** The coverages at the date of loss: an endorsement counts only if it took effect by then. One with no date cannot be applied, and is noted. */
    private static List<Coverage> applicableCoverages(List<Coverage> base, List<Endorsement> endorsements, LocalDate loss, List<String> reasoning) {
        List<Coverage> out = new ArrayList<>(base == null ? List.of() : base);
        if (endorsements == null) {
            return out;
        }
        for (Endorsement e : endorsements) {
            if (e.effectiveDate() == null || loss == null) {
                reasoning.add("Endorsement on " + e.coverageType() + " has no effective date to compare, so it was not applied. A person must check.");
                continue;
            }
            if (e.effectiveDate().isAfter(loss)) {
                reasoning.add("Endorsement on " + e.coverageType() + " takes effect on " + e.effectiveDate() + ", after the loss: not applied");
                continue;
            }
            out.removeIf(c -> c.type().equals(e.coverageType()));
            if (!Endorsement.REMOVE.equals(e.change())) {
                out.add(new Coverage(e.coverageType(), e.limit(), e.deductible()));
            }
            reasoning.add("Endorsement effective " + e.effectiveDate() + " applied: " + e.change() + " " + e.coverageType());
        }
        return out;
    }
}
