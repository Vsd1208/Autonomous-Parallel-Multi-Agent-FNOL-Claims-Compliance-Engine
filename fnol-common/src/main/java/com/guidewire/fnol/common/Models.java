package com.guidewire.fnol.common;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Models {
  private Models() {}
  public record FNOLPayload(@NotBlank String policyNumber, @NotNull LocalDate incidentDate, @NotBlank String state,
      @NotBlank String description, String claimantName, String claimantDob, String claimantSsn, List<String> evidenceUris) {}
  public record Coverage(String type, BigDecimal limit, BigDecimal deductible) {}
  public record Policy(String policyNumber, String policyType, String status, LocalDate effectiveDate, LocalDate expirationDate,
      String insuredName, String state, String riskDescription, List<Coverage> coverages) {}
  public record ClaimHistory(String claimId, LocalDate lossDate, String status, BigDecimal paidAmount, String description) {}
  public record Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
      BigDecimal estimatedDamage, BigDecimal reserve) {}
  public record DamageAssessment(String damageType, String severity, BigDecimal estimatedDamage, BigDecimal confidence) {}
  public record ReserveResult(BigDecimal estimatedDamage, BigDecimal factor, BigDecimal recommendedReserve) {}
  public record PIIResult(String redactedDescription, List<String> detectedTypes, boolean piiDetected) {}
  public record DeadlineResult(String state, LocalDate deadline, String ruleSource, String disclaimer) {}
  public record SubrogationResult(int score, String recommendedAction, Map<String,Integer> factors) {}
  public record PolicyValidationResult(String policyNumber, boolean policyFound, boolean policyActive, boolean relevantCoverageFound,
      boolean covered, String coverageType, BigDecimal coverageLimit, BigDecimal deductible, List<String> reasoning) {}
  public record AuditRecord(String eventId, String claimId, String agentName, Instant timestamp, String inputHash, String outputHash, String status) {}
  public record BranchAResult(DamageAssessment damageAssessment, PolicyValidationResult coverage, ReserveResult reserve) {}
  public record BranchBResult(PIIResult pii, DeadlineResult deadline, SubrogationResult subrogation, List<AuditRecord> audit) {}
  public record FNOLResponse(String claimId, String status, BranchAResult branchA, BranchBResult branchB, List<String> explanation) {}
  public record ApiError(Instant timestamp, int status, String error, String message, String path) {}
  public record ClaimCreateRequest(String policyNumber, LocalDate incidentDate, String state, BigDecimal estimatedDamage, BigDecimal reserve) {}
  public record ClaimCreateResponse(String claimId, String status) {}
}
