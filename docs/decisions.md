# Decisions

## ADR-001 Maven multi-module architecture
Separate modules preserve ownership boundaries between contracts, mocks, adapters, agents, guardrails, orchestration, and API.

## ADR-002 Mock Guidewire integration boundary
Guidewire is mocked through HTTP and client interfaces so production adapters can replace mock clients without changing agents or controllers.

## ADR-003 Sequential agent orchestration for Sprint 2
Sequential flow improves debugging and auditability while branch DTOs leave room for later parallel execution.

## ADR-004 PostgreSQL + Flyway
PostgreSQL models the production persistence target; Flyway makes schema changes explicit and reviewable.

## ADR-005 Deterministic mock AI providers
Mock AI behavior is deterministic so tests and demos are repeatable. Real model providers can be swapped behind provider interfaces later.
