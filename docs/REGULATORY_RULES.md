# Regulatory rules

**No rule here has been reviewed by a lawyer. A licensed insurance-law specialist must review every rule before any production use.** `verificationStatus` records what this project read, not a legal opinion. For the rules themselves and their sources, see [legal-compliance.md](legal-compliance.md).

## The model

Rules are data, in `claim-regulations.json` (US) and `claim-regulations-in.json` (India). Each carries:

| Field | Meaning |
|---|---|
| `ruleId`, `ruleVersion` | Which rule, which version |
| State key, `lines`, `claimType` | Jurisdiction, line of business, claim type (`ANY` today) |
| `citation`, `source` | Legal source and URL |
| `effectiveFrom`, `effectiveTo` | When this version applies. **Null `effectiveFrom` means the start date was not researched**, not that the rule is ancient: the rule then applies to any date |
| `trigger` | What starts the clock: `NOTICE` or `PROOF_OF_LOSS` |
| `days`, `dayType`, `holidayCalendar` | The calculation |
| `exceptions` | Exceptions known to this project. Not exhaustive, and not computed |
| `requiredAction` | What must be done |
| `category` | `STATUTORY`, `INSURER_POLICY`, `INTERNAL_TARGET` or `EXPERIMENTAL` |
| `verificationStatus` | `PRIMARY_TEXT_READ`, `SECONDARY_SOURCE` or `UNVERIFIED` |
| `previousVersions` | Earlier versions, each with the same fields |

Today's categories:

- **Statutory:** the five US states' rules.
- **Internal target:** the three-day warning window. It has no legal force.
- **Experimental:** India's clock, off by default.
- **Insurer policy:** none loaded, since each insurer sets its own.

## How a date is reached

1. **The version in force on the claim's date applies:** the notice date for acknowledging and deciding, the loss date for recovery. Today's date plays no part.
2. **With no version in force there is no date**, and a person is told to set it. No older rule is assumed.
3. **Each result lists the rules it used** (`appliedRules`), with kind and how far each was checked. The workspace shows them under Compliance.

### Historical versions

One real history is loaded: Florida's SB 2-A changed acknowledging from 14 to 7 days and pay-or-deny from 90 to 60 days, effective 1 March 2023, according to the regulator's memo [OIR-23-01M](https://content.govdelivery.com/attachments/FLOIR/2023/03/01/file_attachments/2424676/OIR-23-01M.pdf). The old versions rest on that memo (`SECONDARY_SOURCE`). **Open question for a lawyer:** the version is chosen by notice date; whether the policy's date governs instead was not established. The Florida statute page would not load for checking.

Florida's recovery rule starts on 24 March 2023. A loss before that gets no recovery date, and the earlier period is not guessed.

**Other open question for a lawyer:** § 627.70131 is a property insurance section, yet the Florida acknowledgement rule is applied to auto claims too. If it does not apply to auto, the date shown is merely an early target.

### Public holidays

A holiday is skipped only when the rule's own text excludes holidays and the rule names a calendar that covers the years involved (`regulatory-holidays.json`).

| State | Text | Calendar | Effect |
|---|---|---|---|
| NY | 11 NYCRR 216.1 excludes state legal holidays | 2026, from the [Department of Civil Service](https://www.cs.ny.gov/attendance_leave/2026_legal_holidays.cfm) | Skipped in 2026. **2027 is not loaded** |
| TX | Tex. Ins. Code § 542.051(1): a holiday "recognized by this state" | **None:** the State Auditor's schedule refused the connection on 4 Nov 2026 | Weekends only |
| IL | "Working days": the definition was not checked | None | Weekends only |

**Failure direction.** When a calendar is missing or does not cover the period, the count uses weekends only. That gives an earlier date, and the result says so. A missing calendar can make a deadline look sooner than the law requires; it should not make one missed. A person must add each new year: no automation or expiry alert exists.

## Tests

`RegulatoryRuleVersionsTest` and `CitedRegulationsTest`: the last and first day of a Florida version, a claim's date choosing the version rather than today's, recovery boundaries, no version in force, holidays, Friday to Monday, a period running past a calendar's last year, the Texas blocker, and completeness of the data: every rule has its fields, no duplicate version, no overlap, nothing claims a lawyer's review.

## Not done

Only CA, NY, TX, FL and IL, plus Maharashtra's demo state under India's clock. No tolling. No rules by claim type. No update feed: a change in the law reaches this system only when a person edits the file. Interest and penalties are not computed.
