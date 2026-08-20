# Architecture

The platform is an AI-assisted FNOL decision-support system. Controllers accept requests, `FNOLOrchestrator` owns the workflow, agents own individual decisions, and `PolicyCenterClient` / `ClaimCenterClient` define the Guidewire adapter boundary.

Sprint 2 uses deterministic sequential orchestration for reliability and observability. The branch result objects (`BranchAResult`, `BranchBResult`) preserve a future path to parallel branch execution without changing API contracts.
