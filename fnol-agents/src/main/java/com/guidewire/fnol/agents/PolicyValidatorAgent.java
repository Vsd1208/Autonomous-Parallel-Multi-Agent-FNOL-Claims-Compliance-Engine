package com.guidewire.fnol.agents;

import com.guidewire.fnol.agents.CoverageRuleProvider.CoverageRule;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Branch A coverage check: the policy must exist, be ACTIVE and inside its term today, and carry
 * a coverage that the line-of-business rule accepts (e.g. AUTO: COLLISION or COMPREHENSIVE).
 */
@Component
public class PolicyValidatorAgent implements FNOLAgent<String, PolicyValidationResult> {

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

    @Override
    public PolicyValidationResult execute(String policyNumber) {
        Policy policy = pc.getPolicy(policyNumber);
        if (policy == null) {
            return new PolicyValidationResult(policyNumber, false, false, false, false, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of("Policy not found in PolicyCenter"));
        }

        List<String> reasoning = new ArrayList<>();
        reasoning.add("PolicyCenter policy found: line of business " + policy.policyType());

        LocalDate today = LocalDate.now(clock);
        boolean active = "ACTIVE".equals(policy.status())
                && (policy.effectiveDate() == null || !today.isBefore(policy.effectiveDate()))
                && (policy.expirationDate() == null || !today.isAfter(policy.expirationDate()));
        reasoning.add(active
                ? "Policy is ACTIVE and in term (" + policy.effectiveDate() + " to " + policy.expirationDate() + ")"
                : "Policy is not active today: status " + policy.status() + ", term "
                        + policy.effectiveDate() + " to " + policy.expirationDate());

        CoverageRule rule = rules.ruleFor(policy.policyType());
        reasoning.add("Coverage rule: " + rule.describe());

        List<Coverage> coverages = pc.getCoverages(policyNumber);
        Optional<Coverage> match = rule.acceptedCoverageTypes().stream()
                .flatMap(type -> coverages.stream().filter(c -> type.equals(c.type())))
                .findFirst();
        reasoning.add(match.map(c -> "Matched coverage " + c.type() + " (limit " + c.limit() + ", deductible " + c.deductible() + ")")
                .orElse("No coverage on the policy satisfies the rule"));

        return new PolicyValidationResult(
                policyNumber,
                true,
                active,
                match.isPresent(),
                active && match.isPresent(),
                match.map(Coverage::type).orElse(null),
                match.map(Coverage::limit).orElse(BigDecimal.ZERO),
                match.map(Coverage::deductible).orElse(BigDecimal.ZERO),
                List.copyOf(reasoning));
    }
}
