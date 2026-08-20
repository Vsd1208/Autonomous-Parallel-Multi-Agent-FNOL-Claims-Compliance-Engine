# Agent Design

Agents implement focused responsibilities and return structured outputs:

- `VisionDamageAgent`
- `PolicyValidatorAgent`
- `ReserveCalculatorAgent`
- `PiiRedactionAgent`
- `StatutoryDeadlineAgent`
- `SubrogationScorerAgent`
- `AuditTrailAgent`

Mock AI providers are deterministic for Sprint 2. They are designed as replaceable provider/scorer abstractions rather than controller logic.
