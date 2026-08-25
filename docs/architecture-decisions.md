# Architecture Decision Records (ADRs)

---

## ADR-001: Multi-Module Maven Architecture
- **Status**: Accepted
- **Context**: The platform consists of independent concerns: contracts, mock Guidewire systems, enrichment adapters, AI agents, guardrails, orchestration, API entry point, and UI.
- **Decision**: Use a Maven multi-module structure with a single parent POM managing versions (Java 17, Spring Boot 3.2.x, Jackson, JUnit 5) and dependency management.
- **Consequences**: Strict separation of concerns; prevents leakage between mock services and platform code; ensures clean build and deployment boundaries.

---

## ADR-002: Guidewire Integration Abstraction via Client Adapters
- **Status**: Accepted
- **Context**: Access to a live Guidewire Cloud Platform tenant is not assumed. The architecture must remain honest about simulation while providing a friction-free path to production integration.
- **Decision**: Define pure Java interfaces (`PolicyCenterClient`, `ClaimCenterClient`). The orchestrator and agents interact *only* with these interfaces. `MockPolicyCenterClient` and `MockClaimCenterClient` communicate over HTTP to the `fnol-mock-guidewire` service.
- **Consequences**: Swapping to live Guidewire Cloud APIs requires only implementing `GuidewirePolicyCenterClient` and `GuidewireClaimCenterClient` without modifying agents, orchestrators, controllers, or frontend code.

---

## ADR-003: Contract-First Mock Guidewire Services
- **Status**: Accepted
- **Context**: The simulation must behave as an authentic external dependency, exercising network serialization, HTTP error handling, and timeout behavior.
- **Decision**: Implement `fnol-mock-guidewire` as an isolated Spring Boot HTTP server exposing realistic endpoints (`/pc/policies/...`, `/cc/claims`).
- **Consequences**: Real HTTP client-server boundaries are exercised; Postman tests and integration suites validate authentic wire contracts.

---

## ADR-004: Sequential Agent Orchestration with Branch DTOs
- **Status**: Accepted
- **Context**: Premature concurrency can obscure debugging, introduce race conditions, and complicate audit logging.
- **Decision**: Execute Branch A (Operational) and Branch B (Compliance) sequentially in Sprint 2 while returning structured `BranchAResult` and `BranchBResult` DTOs.
- **Consequences**: Deterministic, observable, easily testable execution flow. Provides an effortless upgrade path to `Mono.zip()` / `CompletableFuture` parallel fan-out in Sprint 3 without breaking DTO contracts.

---

## ADR-005: Deterministic AI Providers for Testability
- **Status**: Accepted
- **Context**: Non-deterministic LLM calls during automated unit tests lead to flaky builds and unpredictable demo runs.
- **Decision**: Implement deterministic mock providers (`MockVisionProvider`, `SyntheticDeadlineRuleProvider`, `DeterministicSubrogationScorer`) behind clean provider interfaces.
- **Consequences**: Tests achieve 100% repeatability and high test coverage. Real multimodal vision models (GPT-4o Vision, Claude 3.5 Sonnet) can be slotted behind `VisionProvider` via configuration.

---

## ADR-006: Auditability & Explainability by Design
- **Status**: Accepted
- **Context**: Insurance regulatory standards (NAIC Model Audit Rule, GDPR, CCPA) require explainable decisions, PII protection, and immutable records.
- **Decision**: Generate SHA-256 hashes of all agent inputs and outputs, record every event in the `audit_trail` table, enforce output explainability strings, and sanitize PII before persistence.
- **Consequences**: Decisions are transparent, verifiable, and explainable to adjusters and regulatory reviewers.
