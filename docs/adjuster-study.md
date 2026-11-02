# Evidence from real adjusters: study kit

**Status: no study has been run. There are no results yet.** Every figure about adjusters using this tool is still unknown,
including whether it saves time. This page is how to find out, and the results table stays empty until it is real.

Interview questions for the wider pain points: [STAKEHOLDER_INTERVIEW_TEMPLATE.md](STAKEHOLDER_INTERVIEW_TEMPLATE.md).

## What the product measures

For a **signed-in** person's decision only (scripts and integrations do not count), each decision stores:

| Measure | Meaning |
|---|---|
| Handling time | Seconds from opening the claim to recording the decision, as the workspace saw it. Includes interruptions, so read medians |
| Advisory usefulness | The adjuster's optional 1-5 rating of the AI advisory |
| Already stored | Whether they changed the reserve, confirmed or corrected the damage, and their decision next to the AI's |

The **AI Governance** page shows these under "Evidence from adjusters", and `GET /api/v1/ai/metrics` returns them as
`adjusterEvidence`. Test sign-ins count too, so start a study on an empty database with accounts made for it.

## Running a small study

**Who:** 3-5 licensed adjusters or claims professionals. Fewer gives anecdotes, which are still worth reporting as anecdotes.

**Consent:** explain the purpose, that the data is synthetic, what is recorded, and that they can stop at any time. Ask
your university whether it needs ethics approval. Report no names.

**Sessions (about 45 minutes each):**

1. Set up: `docker compose down -v`, then one `FNOL_USERS` account per person.
2. **Without the AI advisory (the baseline):** 3 prepared claims, from the photos and report alone. Time each one by hand.
   Without this there is nothing to compare with, and any "time saved" figure is a guess.
3. **With the workspace:** 3 different, comparable claims. Swap which claims come first between people, to reduce order effects.
4. Afterwards, ask:
   - Where did you distrust the AI, and were you right?
   - What would you need before relying on this?
   - What is missing compared with how you work in ClaimCenter?
   - Would you use it? Why or why not?

**Report honestly:** number of people and claims, every result including the bad ones, and the limits: synthetic claims, a
small sample, and a lab setting rather than real work.

## Results

| Measure | Without AI | With the workspace | People / claims |
|---|---|---|---|
| Median handling time | not measured | not measured | 0 / 0 |
| Agreed with the AI's routing | n/a | not measured | 0 / 0 |
| Changed the recommended reserve | n/a | not measured | 0 / 0 |
| Advisory usefulness (mean, 1-5) | n/a | not measured | 0 / 0 |

Quotes and findings: none yet.
