package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.PolicyHistoryContext;
import com.guidewire.fnol.common.Models.ClaimHistory;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.List;

@Component
public class ClaimHistoryEnricher {

    private final PolicyCenterClient policyClient;

    public ClaimHistoryEnricher(PolicyCenterClient policyClient) {
        this.policyClient = policyClient;
    }

    public PolicyHistoryContext enrich(String policyNumber) {
        List<ClaimHistory> history = policyClient.getPolicyClaimHistory(policyNumber);

        int totalClaims = history.size();
        int last6Months = countClaimsInLast(history, 180);
        int last12Months = countClaimsInLast(history, 365);
        int openClaims = countOpenClaims(history);

        String frequencyRisk = computeFrequencyRisk(last6Months, last12Months);

        ClaimHistory mostRecent = history.isEmpty() ? null : history.get(0);

        return new PolicyHistoryContext(
                totalClaims,
                last6Months,
                last12Months,
                mostRecent != null ? mostRecent.lossDate() : null,
                mostRecent != null ? mostRecent.status() : null,
                mostRecent != null ? mostRecent.paidAmount() : null,
                openClaims,
                frequencyRisk
        );
    }

    private String computeFrequencyRisk(int last6Months, int last12Months) {
        if (last12Months == 0) return "LOW";
        if (last12Months == 1 && last6Months == 0) return "MEDIUM";
        return "HIGH";
    }

    private int countClaimsInLast(List<ClaimHistory> history, int days) {
        LocalDate cutoff = LocalDate.now().minusDays(days);
        return (int) history.stream()
                .filter(c -> c.lossDate().isAfter(cutoff))
                .count();
    }

    private int countOpenClaims(List<ClaimHistory> history) {
        return (int) history.stream()
                .filter(c -> "OPEN".equals(c.status()))
                .count();
    }
}