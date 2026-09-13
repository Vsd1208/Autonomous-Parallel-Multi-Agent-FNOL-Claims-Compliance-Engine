package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.ClaimHistory;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.Policy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class MockPolicyCenterClient implements PolicyCenterClient {
    private RestTemplate restTemplate;
    private String baseUrl;

    public MockPolicyCenterClient() {
        this.restTemplate = null;
        this.baseUrl = "http://localhost:9090";
    }

    public MockPolicyCenterClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }
    /**
     * Mock implementation: Returns synthetic policy data for testing
     */
    @Override
    public Policy getPolicy(String policyNumber) {
        // Return mock policy
        return new Policy(
                policyNumber,
                "AUTO",
                "ACTIVE",
                LocalDate.now().minusDays(365),
                LocalDate.now().plusDays(365),
                "John Doe",
                "CA",
                "2020 Toyota Camry",
                List.of()
        );
    }

    /**
     * Mock implementation: Returns synthetic coverage data for testing
     */
    @Override
    public List<Coverage> getCoverages(String policyNumber) {
        // Return mock coverages
        return List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000")),
                new Coverage("LIABILITY", new BigDecimal("100000"), new BigDecimal("500")),
                new Coverage("COMPREHENSIVE", new BigDecimal("30000"), new BigDecimal("250"))
        );
    }

    /**
     * Mock implementation: Returns synthetic policy history for testing
     */
    @Override
    public List<ClaimHistory> getPolicyHistory(String policyNumber) {
        // Same as getPolicyClaimHistory
        return getPolicyClaimHistory(policyNumber);
    }

    /**
     * Mock implementation: Returns synthetic claim history for testing
     */
    @Override
    public List<ClaimHistory> getPolicyClaimHistory(String policyNumber) {
        // Return synthetic data based on policy number
        if ("POL-AUTO-001".equals(policyNumber)) {
            return List.of();  // No claims
        } else if ("POL-AUTO-002".equals(policyNumber)) {
            return List.of(
                    new ClaimHistory(
                            "CLM-001",
                            LocalDate.now().minusDays(200),
                            "CLOSED",
                            new BigDecimal("1800"),
                            "Theft"
                    )
            );
        } else if ("POL-AUTO-003".equals(policyNumber)) {
            return List.of(
                    new ClaimHistory(
                            "CLM-001",
                            LocalDate.now().minusDays(45),
                            "CLOSED",
                            new BigDecimal("2500"),
                            "Collision"
                    ),
                    new ClaimHistory(
                            "CLM-002",
                            LocalDate.now().minusDays(200),
                            "CLOSED",
                            new BigDecimal("1800"),
                            "Theft"
                    )
            );
        } else if ("POL-AUTO-004".equals(policyNumber)) {
            return List.of(
                    new ClaimHistory(
                            "CLM-001",
                            LocalDate.now().minusDays(45),
                            "CLOSED",
                            new BigDecimal("1500"),
                            "Collision"
                    )
            );
        } else if ("POL-AUTO-005".equals(policyNumber)) {
            return List.of(
                    new ClaimHistory("CLM-001", LocalDate.now().minusDays(10), "OPEN", new BigDecimal("5000"), "Collision"),
                    new ClaimHistory("CLM-002", LocalDate.now().minusDays(30), "CLOSED", new BigDecimal("2000"), "Theft"),
                    new ClaimHistory("CLM-003", LocalDate.now().minusDays(60), "OPEN", new BigDecimal("3000"), "Collision")
            );
        } else if ("POL-AUTO-006".equals(policyNumber)) {
            LocalDate recentDate = LocalDate.now().minusDays(10);
            return List.of(
                    new ClaimHistory("CLM-001", recentDate, "OPEN", new BigDecimal("5000"), "Collision"),
                    new ClaimHistory("CLM-002", LocalDate.now().minusDays(200), "CLOSED", new BigDecimal("2000"), "Theft")
            );
        }

        // Default: return empty list
        return List.of();
    }
}