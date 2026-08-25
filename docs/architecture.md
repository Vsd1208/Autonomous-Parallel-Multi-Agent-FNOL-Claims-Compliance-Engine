# FNOL Intelligence Platform — System Architecture

## Overview
The **Autonomous Parallel Multi-Agent FNOL Claims & Compliance Engine** is an enterprise decision-support platform for Property & Casualty (P&C) insurance intake. It evaluates visual damage, policy coverage, reserve sizing, PII redaction, statutory deadlines, subrogation potential, and audit logging.

## Core Architectural Layers

```
                     +---------------------------------------+
                     |         Jutro Digital Portal          |
                     |       (React + Vite + Tailwind)       |
                     +---------------------------------------+
                                         |
                                         | HTTP POST /api/v1/fnol/intake
                                         v
                     +---------------------------------------+
                     |           FNOL REST API               |
                     |           (Spring Boot 3)             |
                     +---------------------------------------+
                                         |
                                         v
                     +---------------------------------------+
                     |         Input Guardrails              |
                     +---------------------------------------+
                                         |
                                         v
                     +---------------------------------------+
                     |          FNOL Orchestrator            |
                     +---------------------------------------+
                                    /         \
                                   /           \
                                  v             v
             +-----------------------+       +-----------------------+
             |       BRANCH A        |       |       BRANCH B        |
             |      Operational      |       |  Legal & Compliance   |
             +-----------------------+       +-----------------------+
             | - VisionDamageAgent   |       | - PiiRedactionAgent   |
             | - PolicyValidatorAgent|       | - StatutoryDeadline   |
             | - ReserveCalculator   |       | - SubrogationScorer   |
             +-----------------------+       | - AuditTrailAgent     |
                         |                   +-----------------------+
                         |                               |
                         +---------------+---------------+
                                         |
                                         v
                     +---------------------------------------+
                     |        Output & PII Guardrails        |
                     +---------------------------------------+
                                         |
                                         v
                     +---------------------------------------+
                     |       ClaimCenter Client Adapter      |
                     |    (HTTP POST /cc/claims -> Mock CC)  |
                     +---------------------------------------+
                                         |
                                         v
                     +---------------------------------------+
                     |        PostgreSQL Persistence         |
                     |      (claims + audit_trail tables)    |
                     +---------------------------------------+
```

## Maven Module Structure

- **`fnol-common`**: Shared immutable records (DTOs) and domain contracts.
- **`fnol-mock-guidewire`**: Standalone HTTP microservice mimicking PolicyCenter and ClaimCenter REST endpoints.
- **`fnol-enrichment`**: External client adapters (`PolicyCenterClient`, `ClaimCenterClient`) and policy history enrichment.
- **`fnol-agents`**: Focused, single-responsibility AI agent implementations.
- **`fnol-guardrails`**: Deterministic validation barrier protecting against invalid inputs, negative reserves, and PII leakage.
- **`fnol-orchestrator`**: Orchestrates agent workflow execution, guardrails, and claim assembly.
- **`fnol-api`**: Main Spring Boot entry point, REST controllers, exception handlers, and Flyway persistence.
- **`frontend`**: Modern Juilio-style React UI console.

## Human-in-the-Loop Philosophy
The system is built strictly for **decision support**. Final adjudication authority rests with human claims adjusters. Every recommendation includes human-readable explainability factors and SHA-256 hashed audit events.
