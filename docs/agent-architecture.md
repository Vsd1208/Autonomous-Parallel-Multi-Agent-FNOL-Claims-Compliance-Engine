# Agent Architecture & Provider Design

## Overview
Every AI and rule-based evaluation in the FNOL Intelligence Platform follows the `FNOLAgent<I, O>` contract:

```java
public interface FNOLAgent<I, O> {
    O execute(I input);
}
```

Each agent:
- Has a single, focused responsibility
- Accepts typed input records
- Returns typed output records
- Is independently testable without Spring context or external dependencies
- Uses dependency injection with interface abstractions for underlying providers

---

## Branch A — Operational Loss Triage

### 1. `VisionDamageAgent`
- **Responsibility**: Analyzes vehicle loss photos / evidence to extract damage classification, severity, estimated repair cost, and confidence score.
- **Provider Interface**: `VisionProvider`
- **Current Implementation**: `MockVisionProvider` (deterministic, synthetic estimation).
- **Extensibility**: Designed to be replaced with a multimodal vision LLM provider (e.g. GPT-4o Vision or Claude 3.5 Sonnet) via `VisionProvider` without modifying agent logic.

### 2. `PolicyValidatorAgent`
- **Responsibility**: Queries PolicyCenter via `PolicyCenterClient` to verify policy existence, active status, effective dates, and line-of-business coverage applicability (e.g. Collision coverage).
- **Output**: `PolicyValidationResult` with explicit reasoning list for explainability.

### 3. `ReserveCalculatorAgent`
- **Responsibility**: Calculates preliminary loss reserve recommendation.
- **Formula**: `recommendedReserve = estimatedDamage * 1.15` (15% ULAE buffer).
- **Type Safety**: Strictly uses `java.math.BigDecimal` with `RoundingMode.HALF_UP`. Never uses `double`.
- **Explainability**: Outputs the raw estimated damage, the explicit factor (1.15), and the final recommended reserve.

---

## Branch B — Legal & Regulatory Compliance

### 1. `PiiRedactionAgent`
- **Responsibility**: Detects and sanitizes sensitive Personally Identifiable Information (PII) before storage or downstream processing.
- **Entities Detected**: Social Security Numbers (SSN), Dates of Birth (DOB), and Claimant Full Names.
- **Compliance**: Aligns with GDPR Article 25 and CCPA redaction requirements.
- **Output**: `PIIResult` with sanitized description, detected entity types list, and boolean flag.

### 2. `StatutoryDeadlineAgent`
- **Responsibility**: Computes state-mandated claim acknowledgement and decision deadlines.
- **Provider Interface**: `DeadlineRuleProvider`
- **Supported States**: CA (30 days), NY (35 days), TX (15 days), FL (20 days), IL (30 days).
- **Output**: `DeadlineResult` with statutory deadline date, rule source identifier, and regulatory disclaimer.

### 3. `SubrogationScorerAgent`
- **Responsibility**: Evaluates third-party liability signals and recommends investigation or monitoring.
- **Scorer Interface**: `SubrogationScorer`
- **Scoring Breakdown**:
  - Third-party mentions ("other driver", "rear-ended"): +35 pts
  - Police report filing: +20 pts
  - Moderate/Severe damage: +23 pts
- **Output**: Score constrained between 0–100, recommended action (`INVESTIGATE` if ≥ 70, else `MONITOR`), and map of contributing factor weights.

### 4. `AuditTrailAgent`
- **Responsibility**: Generates immutable audit records for regulatory compliance (NAIC Model Audit Rule).
- **Cryptographic Integrity**: Computes SHA-256 hashes of both input and output payloads.
- **Output**: `AuditRecord` stored in the `audit_trail` table.
