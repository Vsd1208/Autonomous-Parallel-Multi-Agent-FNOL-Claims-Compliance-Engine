# Legal and compliance checks

The legal branch (Branch B) runs in parallel with the damage assessment, so these checks add no waiting. Everything in
it is rule-based and explainable. **Nothing here is legal advice:** the rule set is a prototype compiled on 29 Oct 2026
and must be confirmed by an insurance-law specialist before use. Rules change; the rule set carries a version and every
claim records which version it used.

## 1. Regulatory claim clock (cited rules as data)

`fnol-agents/src/main/resources/claim-regulations.json`, applied by `CitedDeadlineRuleProvider`.

- **Acknowledge** from notice of the claim (the day the insurer receives it), not from the accident.
- **Accept or deny** from notice or from a complete proof of loss, as each rule says. While photos are missing
  (`INCOMPLETE_PHOTO_SET`, `MISSING_EVIDENCE`) that clock has not started; it starts when the claimant's requested photos
  arrive through the upload link, which also adds a ClaimCenter activity and an audit record.
- Business days skip Saturdays and Sundays only (public holidays are not excluded).
- States without a loaded rule get no dates and a "set the deadlines manually" note; a state without a fixed decision
  period says so.

| State | Acknowledge | Accept or deny | Recovery limit (subrogation) |
|---|---|---|---|
| CA | 15 calendar days from notice, 10 CCR § 2695.5(e) | 40 calendar days from proof of claim, 10 CCR § 2695.7(b) | 3 years, CCP § 338(c) * |
| NY | 15 business days from notice, 11 NYCRR 216.4(a) | 15 business days from proof of loss and requested items, 11 NYCRR 216.6(c) | 3 years, CPLR 214(4) * |
| TX | 15 days from notice, Ins. Code § 542.055 | 15 business days from final proof of loss items, Ins. Code § 542.056 | 2 years, CPRC § 16.003 |
| FL | 7 calendar days, Fla. Stat. § 627.70131(1)(a) | 60 days from notice, residential property only, § 627.70131(7)(a) | 2 years, § 95.11(4)(a) after HB 837 † |
| IL | 15 working days, 50 Ill. Adm. Code 919.40 | No fixed period: "a reasonable time", 919.50(a) | 5 years, 735 ILCS 5/13-205 * |

\* From background knowledge, not verified against the statute text. † § 95.11(3)(g) still states 4 years for injury to
personal property; the shorter period is used so the recovery right is not lost.

Sources consulted: [Cal. Code Regs. tit. 10 § 2695.5](https://www.law.cornell.edu/regulations/california/10-CCR-2695.5),
[§ 2695.7](https://www.law.cornell.edu/regulations/california/10-CCR-2695.7),
[11 NYCRR 216.4](https://regulations.justia.com/states/new-york/title-11/chapter-ix/part-216/section-216-4/),
[11 NYCRR 216.6](https://regulations.justia.com/states/new-york/title-11/chapter-ix/part-216/section-216-6),
[Tex. Ins. Code § 542.055](https://texas.public.law/statutes/tex._ins._code_section_542.055),
[§ 542.056](https://texas.public.law/statutes/tex._ins._code_section_542.056),
[Fla. Stat. § 627.70131 (2024)](https://www.flsenate.gov/Laws/Statutes/2024/627.70131),
[50 Ill. Adm. Code 919.40](https://ilga.gov/ftp/JCAR/AdminCode/050/050009190000400R.html),
[50 Ill. Adm. Code 919.50](https://regulations.justia.com/states/illinois/title-50/part-919/section-919-50/),
[Florida HB 837 summary (Greenberg Traurig)](https://www.gtlaw.com/en/insights/2023/4/tort-reform-overhauls-floridas-litigation-landscape),
[Florida tort reform and subrogation (JD Supra)](https://production.jdsupra.com/legalnews/florida-s-tort-reform-and-its-impact-on-9867190).

## 2. Unfair-discrimination testing

AI Governance page, `GET /api/v1/ai/metrics` → `fairness`. By state, reporting channel and line of business:
straight-through rate, adjuster approval rate, reserve-changed rate, reserve as a share of the amount claimed, and the
share of claims the fraud anomaly score did not flag (`FRAUD_ANOMALY` is the adverse outcome). Each
rate is compared with the best group's; a ratio below 0.80 is flagged **Review** (the "four-fifths rule"); groups with
fewer than 5 cases are not compared. The engine holds no protected attributes and should not, so this tests proxies
only. It is a monitoring framework in the spirit of the NAIC AI bulletin and Colorado SB21-169, not a finding.

## 3. Written acknowledgement

At intake a plain-language letter is generated from a fixed template (`AcknowledgementLetter`, not AI) and filed on the
ClaimCenter claim: receipt confirmed, claim number, what happens next and by when, what is still needed (missing photo
views), and the rule it satisfies. A represented claimant's letter is addressed to their attorney. The time it was filed
is stored (`claims.acknowledged_at`) and the governance page reports acknowledgement on time against the deadline.
Prototype: the letter is not posted or e-mailed.

## 4. Subrogation

- Wider third-party detection (e.g. "a truck hit my car", "ran the red", "t-boned", a neighbour's tree, a contractor).
- The other party's details are picked out when the description gives them: vehicle plate, insurer, police report
  number. These are third-party data kept on the claim file for the recovery; they are not sent to any external model.
- When recovery is likely, a ClaimCenter activity for the subrogation unit is due on the **recovery limitation
  deadline** (loss date + the state's limitation period), so the right to recover is not lost.

## 5. Attorney representation

Phrases such as "my attorney", "represented by" or "law firm" raise `ATTORNEY_REPRESENTED` at intake: the claim is not
eligible for straight-through processing, its activity goes to the "Litigation and represented claims" queue, the note
tells the adjuster to communicate through the attorney, and the acknowledgement letter is addressed to the attorney.
Keyword detection can miss phrasings or match a mention that is not representation; the adjuster confirms.
