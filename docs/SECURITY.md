# Security

How access works and what it does not cover. Status terms: **tested** means an automated test in this repository exercises it; **not done** means it does not exist. Nothing here has had an external penetration test.

## Who can do what

The server enforces every rule below. The workspace hides what a role cannot use, as a convenience only.

| Role | Claims it can see | Can decide a claim | Governance figures | Inbox and audit administration |
|---|---|---|---|---|
| Adjuster | All, except restricted (SIU) claims it is not assigned | Yes, within its reserve authority | Yes | No |
| Senior adjuster | All | Yes, within its reserve authority | Yes | No |
| Supervisor | All | Yes | Yes | Yes |
| Inspector | Only claims with an inspection assigned to it, and only a limited view | No | No | No |
| Service credential (`X-Adjuster-Token`) | Ingestion and read routes | **Never** | Yes | No |

- **Decisions need a person.** A service credential gets 403 on a decision. It has one fixed identity (`service-credential`), and any name in the request is ignored, so it cannot impersonate an adjuster. `ClaimAccessTest`.
- **No existence leak.** A claim someone may not see answers 404, the same as a claim that does not exist.
- **Fail closed.** `ClaimAccessInterceptor` works from a route table: a route not declared for inspectors is refused for them. New routes start closed.
- **Inspectors see no analysis.** `GET /fnol/{id}/inspection-view` carries basic facts and the inspector's own work. Their report answer carries no recommended payment.
- **Separation of duties.** Whoever files an inspection report cannot decide that claim. Reserves above a person's authority need a second person.

## Public routes (policyholder portal)

| Protection | How | Test |
|---|---|---|
| Attempt limits, across instances | Database rows, by address and by claim (`fnol.portal.max-failures`, `window-seconds`); 429 past the limit | `PortalSecurityTest` |
| Uniform answers | A wrong claim and a wrong policy number look the same | `PortalSecurityTest` |
| Access tokens | Short-lived, signed, revocable, and holding no claim data (`X-Portal-Token`, `POST /api/v1/portal/claims/{id}/access`, `POST /api/v1/portal/access/revoke`) | `PortalSecurityTest` |
| Uploads | Checked by their bytes, not the declared type | `PortalSecurityTest`, `EvidenceStore` |
| Portal bundle | CI fails if it references the adjuster token or adjuster routes; a test checks it shares no page, component or credential code with the workspace | `ci.yml`, `workspace.test.tsx` |

Set `FNOL_PORTAL_TOKEN_SECRET`: without it, tokens last only until a restart and do not work across instances. Production mode refuses to start without it.

## Personal data

- **Claim text.** `PiiRedactionAgent` masks by exact known values (the claimant's name, surname, phone, email, SSN, date of birth) and by pattern (SSN, Aadhaar, PAN, date, MRN, email, phone, card and account numbers, policy and claim numbers, street addresses). It then scans the result again. If anything is left, `PIIGuardrail` blocks the claim and it goes to a person.
- **No invented confidence.** Rules have no calibrated confidence, so `redactionConfidence` is null on new records. The old constant 0.95 is gone. Older records keep their stored number.
- **Before an external model.** Gemini vision receives photos and a fixed prompt, not the claim's text. The optional drafting agent checks everything meant for the model and uses the template, without calling the model, if personal data is present. Its output is scanned too.
- **Photos.** Faces and plates are blurred before a photo leaves. If redaction is unavailable, the photos are not sent and the claim goes to a person (`GeminiVisionProviderTest`).
- **Logs.** A test submits a claim full of personal data and checks none reaches the logs (`ObservabilityTest`). The production log pattern also masks policy numbers, SSNs, emails and phone numbers that slip into a message. Stack traces are not masked.

### Known privacy limits

- **A name not given on the claim is not found.** "Bob Smith hit my car" keeps "Bob Smith": rules cannot tell a stranger's name from other words. A named-entity model would be needed. `PiiDetectionTest.unknownNamesAreNotFound` records this.
- **Dates.** Any `dd/mm/yyyy` date in free text is masked as a date of birth, including an accident date, which can remove useful information.
- **Address and number patterns are US and Indian formats only.** Other countries need their own.
- **Plate detection is weak.** The cascade was trained on one plate style. Recall has not been measured on labelled plates.
- **Face detection recall is unknown.** Rotating photos in testing showed the detector almost never finds a sideways face, so it now runs at all four orientations ([measurements](AI_MODEL_EVALUATION.md#photo-redaction)). Low light and small photos still lose finds.
- **Retention.** No automated retention or deletion schedule exists. A claim's analysis omits the description text, but saved inbox branch results hold redacted text until the event completes, and the inbox rows are not purged.

## Secrets and configuration

- Secrets come from the environment; none is committed. `.env.demo` and `.env.lite` hold demo values only.
- With `FNOL_ENVIRONMENT=production`, `StartupConfigValidator` refuses to start on a missing or demo secret, a demo password, weak password hashing, a mock claims or policy system, or Gemini on with no key. It names the setting, never the value. This stops known bad cases; it does not make a deployment ready.
- Passwords use PBKDF2 (`fnol.security.password-iterations`, default 210,000).
- Guidewire tokens and secrets never reach logs or error messages (`GuidewireSimulatorContractTest`).

## Metrics and probes

`/actuator/health`, `/liveness` and `/readiness` are public and give no detail. `/actuator/prometheus` needs a credential. No other actuator endpoint is exposed. Metric tags are fixed small sets, never a claim, policy or person.

## Not done

Single sign-on with an insurer's identity provider; an SIU role; encryption of claim data at the application level; a web application firewall; automated secret rotation; dependency and container scanning in CI; an external penetration test. A reverse proxy must supply TLS and correct client addresses: the portal's limit by address sees the proxy's address otherwise.
