# Learned repair costs - SYNTHETIC SELF-TEST

**These claims are synthetic** (published midpoint x a random per-area bias x noise). This run only checks that the pipeline recovers a known bias; it says nothing about real repair costs and never writes the app file.

300 evidence-backed vehicle claims (invoice, body-shop estimate or inspection) with a published cell (area x extent band).

Median absolute percentage error, and claims within 25% of the real cost; every learned prediction comes from a fit that never saw the claim.

| Estimate | Median error | Within 25% | Claims |
|---|---|---|---|
| Learned (leave-one-out, k = 1) | 34% | 116/300 | 300 |
| Published table | 50% | 81/300 | 300 |
| AI's own estimate | 83% | 54/300 | 300 |

| Area | Band | Claims | Published estimate | Learned estimate | Learned 90% range |
|---|---|---|---|---|---|
| FRONT_BUMPER | LARGE | 27 | $7,511 | $6,209 | $2,743-$14,057 |
| FRONT_BUMPER | MEDIUM | 21 | $2,195 | $1,474 | $651-$3,336 |
| FRONT_BUMPER | SMALL | 32 | $224 | $195 | $86-$441 |
| REAR_BUMPER | LARGE | 21 | $3,000 | $2,160 | $954-$4,890 |
| REAR_BUMPER | MEDIUM | 28 | $1,303 | $867 | $383-$1,963 |
| REAR_BUMPER | SMALL | 23 | $224 | $156 | $69-$352 |
| SIDE_PANEL | LARGE | 20 | $7,511 | $11,913 | $5,262-$26,970 |
| SIDE_PANEL | MEDIUM | 29 | $982 | $1,880 | $830-$4,255 |
| SIDE_PANEL | SMALL | 24 | $100 | $183 | $81-$413 |
| WINDSHIELD | LARGE | 21 | $1,637 | $1,095 | $484-$2,478 |
| WINDSHIELD | MEDIUM | 27 | $612 | $404 | $179-$915 |
| WINDSHIELD | SMALL | 27 | $95 | $46 | $20-$104 |

Verdict: self-test only (synthetic), the app file is never written.
