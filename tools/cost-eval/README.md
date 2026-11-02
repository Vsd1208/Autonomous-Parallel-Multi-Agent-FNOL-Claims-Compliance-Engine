# Repair-cost estimates

Repair cost drives the reserve, so the engine needs a dollar figure it can defend. No dollar figure in the project was
validated before this: the labelled photos say which area is damaged, not what the repair cost, and Gemini's
estimates are unchecked. This folder holds the two parts that fix that.

## Part 1: published repair-cost ranges (in use now)

`fnol-agents/src/main/resources/repair-cost-ranges.json`: for each vehicle area (front bumper, rear bumper, side
panel, windshield) and visible-extent band (SMALL / MEDIUM / LARGE, from the on-premise model), a dollar range built
only from figures quoted from these sources (retrieved 1 Nov 2026):

| Source | Figures used |
|---|---|
| [AAA, windshield repair or replacement cost](https://www.aaa.com/autorepair/articles/how-much-does-windshield-repair-or-replacement-cost) | chip $60-$100, crack $100-$150, replacement $250-$1,500+, recalibration typically $360 |
| [AAA Newsroom, 14 Dec 2023](https://newsroom.aaa.com/2023/12/fixing-advanced-vehicle-systems-makes-up-over-one-third-of-repair-costs-following-a-crash/) (2023 F-150, Rogue, Camry; OEM parts via CCC Estimating) | minor rear collision: average total repair estimate $1,698 |
| [Repairer Driven News, 19 Dec 2023](https://www.repairerdrivennews.com/2023/12/19/aaa-finds-adas-increase-repair-costs-by-up-to-37/) (same AAA study) | minor front collision $11,708; windshield replacement and calibration $1,439.78 |
| [AAA Newsroom, 25 Oct 2018](https://newsroom.aaa.com/2018/10/new-vehicle-technologies-double-repair-bills-minor-collisions/) | minor front or rear collision with ADAS up to $5,300 |
| [Consumer Reports, 13 Jul 2017](https://www.consumerreports.org/used-cars/how-to-maximize-your-used-cars-curb-appeal/) | paintless dent repair $50-$150 per dent; scratch buffing $100-$200 |
| [Consumer Reports, 11 Jul 2021](https://www.consumerreports.org/car-repair-maintenance/how-to-fix-dented-car-bumper-diy/) | replacement bumper covers can cost more than $1,000 |
| [CCC Crash Course 2026 via Repairer Driven News, 10 Apr 2026](https://www.repairerdrivennews.com/2026/04/10/ccc-report-shows-shift-to-drp-and-ai-appraisals/) | 2025 average total cost of repair $4,818 (preliminary); $5,721 for vehicles six years old or newer |

**What is an assumption:** which published figure applies to each extent band is this project's mapping, written next
to each cell in the file. The extent band is the share of the photo covered by damage, which also depends on how close
the photo was taken. Cost-guide sites without a named publisher were not used.

**How the app uses it** (`RepairCostEstimator`, `FNOLOrchestrator` step A7):
- every vehicle claim shows the range for its area and extent, with links to the sources;
- when Gemini is unavailable and the on-premise model read the photos, the claim is priced at the range's geometric
  mean (instead of a $3,500 placeholder) and the reserve is recalculated; the claim still goes to an adjuster;
- a Gemini estimate more than 2x outside the range raises `ESTIMATE_OUTSIDE_PUBLISHED_RANGE`.

**Replayed on Gemini's recorded answers** (`python -P check_gemini_estimates.py --run v3-subject`,
[results](results/gemini-estimates-v3-subject.md)): of 66 answers, 41 name an area with a range. Gemini's estimate is
inside the range for 17 of 41, and its median is 2.7x the range's midpoint. Flags by tolerance: 2x 12/41 (used), 3x 6/41,
4x 3/41, 5x 3/41. With no true costs this says the two disagree, not which is right: Gemini may overestimate, or
the band mapping may be low.

**What Gemini's figures are** (`python -P stock_figures.py`, [results](results/stock-figures-v3-subject.md)): 66 answers hold
12 distinct values, and 86% fall on five of them. 17 of the 18 "severe" answers are exactly $12,000. So the figure mostly
restates the severity band the prompt defines. That is why the flag tightened from 4x to 2x, and the workspace shows it as
rough next to the range. Asking for parts and labour might do better, but a new prompt invalidates the fitted trust score
until it is re-measured.

## Part 2: learning from this insurer's own repair costs

Every adjuster decision is stored next to what the AI said (table `ai_feedback`). It now also keeps the **repair cost**
(before the deductible) and **what it rests on** (migration `V18`, column `cost_source`):

| Source | How it gets there | Teaches the model? |
|---|---|---|
| `INSPECTION` | The latest completed inspection's accident-related amount (filled in automatically) | Yes |
| `INVOICE`, `BODY_SHOP_ESTIMATE` | The adjuster enters the figure and names the document | Yes |
| `ADJUSTER_JUDGEMENT` | The adjuster's own figure, or any figure entered without naming evidence | **No**: recorded and counted only |
| `INVESTIGATION_HOLD` | Any figure entered on a claim sent to investigation, before the investigator reports | **No**: held |
| `INVESTIGATION` | The investigator's offline report (accident-related cost, earlier damage excluded) on a claim sent to investigation; written onto the claim's latest decision when the report arrives, and kept on later decisions | Yes, and it outranks any other figure for that claim |

When the adjuster raises or lowers the reserve, the form proposes the repair cost it implies (reserve + deductible) as
their judgement; they correct it if the claimed amount or the limit capped the reserve, and choose the evidence if they
have it. Judgements never train the model, so it cannot learn its own guesses or an accepted AI figure. The AI
governance page counts costs by source and measures the estimates' accuracy on the evidence-backed ones only.

`fit_costs.py` learns from them:

- per area x band, the log repair cost is the published log-midpoint shrunk toward the cell's own claims, weighted as k
  pseudo-claims (k chosen by leave-one-out error); the range is the estimate times/divided by the 90% residual spread;
- one label per claim (its latest decision with a cost), and only evidence-backed ones (`INVOICE`,
  `BODY_SHOP_ESTIMATE`, `INSPECTION`, `INVESTIGATION`);
- every claim is predicted by a fit that never saw it, and compared with the published table and the AI's estimate;
- **shipping rules** for `--write-app`: at least 50 claims with a real cost; lower median absolute percentage error than
  the published table; at least as many claims within 25%. Only cells with 3 or more claims are written
  (`repair-cost-learned.json`); the app uses the published range for the rest and labels which is which.

```bash
python -P fit_costs.py                  # export ai_feedback (compose stack running), report, no app change
python -P fit_costs.py --write-app      # same, and write the learned table if the shipping rules pass
python -P fit_costs.py --simulate 300   # pipeline self-test on SYNTHETIC costs (never writes the app)
```

**Self-test (synthetic, 300 claims with a hidden per-area bias):** leave-one-out median error 34% learned vs 50% for
the published table ([results/report-costs-selftest.md](results/report-costs-selftest.md)). This only shows the
method recovers a known pattern; real accuracy needs real claims.
