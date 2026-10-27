# Sample image credits

Real damage photos for manual testing. All are public domain; no permission is needed to use them.

| File | Source | License |
|---|---|---|
| `front-bumper-dent.jpg` | Humans in the Loop, *Car damages dataset*, image 894 | CC0 1.0 |
| `side-door-damage.jpg` | Humans in the Loop, *Car damages dataset*, image 102 | CC0 1.0 |
| `rear-bumper-damage.jpg` | Humans in the Loop, *Car damages dataset*, image 717 | CC0 1.0 |
| `blurry-front-bumper.jpg` | Humans in the Loop, *Car damages dataset*, image 129, Gaussian blur added to test the low-quality path | CC0 1.0 |
| `ceiling-water-damage.jpg` | Wikimedia Commons, [Ceiling sheetrock damaged by water so paint was peeling](https://commons.wikimedia.org/wiki/File:Ceiling_sheetrock_damaged_by_water_so_paint_was_peeling.jpg), by Tomwsulcer | CC0 1.0 |

The car photos come from the dataset used by `tools/vision-eval` (see its README for the download).
Images were resized to at most 1024 px and re-encoded, which also removes camera metadata.
The four car photos are also in the `tools/vision-eval` sample, so results on them are not an independent test of the calibration.

## Full FNOL photo set: `fnol-set-corolla-side-impact/`

Six photos of **one** vehicle, a 2020 Toyota Corolla LE, after a side impact on the driver's side (NHTSA NCAP
side-impact test 10798, MGA Research, 7 August 2019). Use them together as one claim's standard photo set.

| File | Slot | NHTSA photo |
|---|---|---|
| `front.jpg` | Front | No. 004, Post-Test Frontal View |
| `rear.jpg` | Rear | No. 012, Post-Test Rear View |
| `left.jpg` | Left side (struck side) | No. 008, Post-Test Left Side View |
| `right.jpg` | Right side | No. 014, Post-Test Right Side View |
| `close-up.jpg` | Damage close-up | No. 020, Post-Test Close-Up View of Impact Point |
| `vin-label.jpg` | Odometer / VIN | No. 092, Vehicle Certification Label |

Source: NHTSA Vehicle Crash Test Database (`https://nrd.api.nhtsa.dot.gov/nhtsa/vehicle`, test 10798). Work of the
U.S. federal government, **public domain** (data.gov lists the database as `us-pd`). Resized to 1280 px.
These are crash-lab photos: the car carries NHTSA placards and measuring tape, and the struck side was hit by a
moving barrier at 62 km/h, so expect a severe assessment.

## More full FNOL photo sets (same source, same licence)

Each folder holds the standard set for **one** vehicle after an NHTSA crash test: `front.jpg`, `rear.jpg`, `left.jpg`,
`right.jpg`, `close-up.jpg` and `vin-label.jpg`. In the claim form, **Load real photo set** fills all six slots.
Where a test has no straight rear view, the rear slot uses the rear three-quarter view; where the test's close-up shows
only the NHTSA placard, the close-up slot uses the three-quarter view of the struck corner. Source: NHTSA Vehicle
Crash Test Database (`https://nrd.api.nhtsa.dot.gov/nhtsa/vehicle`), U.S. government work, public domain (`us-pd`).

| Folder | NHTSA test | Vehicle | Impact | Damaged area |
|---|---|---|---|---|
| `fnol-set-camry-frontal/` | 10146 | 2018 Toyota Camry LE sedan | full frontal barrier impact, 56 km/h (NCAP frontal) | front |
| `fnol-set-civic-rear-impact/` | 10121 | 2017 Honda Civic LX sedan | rear impact by a moving barrier, 79 km/h, 70% overlap (FMVSS 301 rear) | rear |
| `fnol-set-equinox-side-pole/` | 10147 | 2018 Chevrolet Equinox SUV | 75° oblique rigid-pole impact on the driver side (NCAP side pole) | left side |
| `fnol-set-altima-offset-frontal/` | 10071 | 2017 Nissan Altima sedan | oblique offset frontal impact into the front-left corner, 90 km/h, 15°, 35% overlap (NHTSA research) | front-left corner |
| `fnol-set-f150-frontal/` | 10175 | 2018 Ford F-150 SuperCrew pickup | full frontal barrier impact, 56 km/h (NCAP frontal) | front |

Photos used (NHTSA photo names):

- `fnol-set-camry-frontal`: Front: Photo No. 009 - Post-Test Front View of Test Vehicle; Rear: Photo No. 017 - Post-Test Left Rear 3-4 View; Left side: Photo No. 011 - Post-Test Left View of Test Vehicle; Right side: Photo No. 013 - Post-Test Right View of Test Vehicle; Damage close-up: Photo No. 015 - Post-Test Right Front 3-4 View; Odometer / VIN: Photo No. 004 - Manufacturer Label
- `fnol-set-civic-rear-impact`: Front: No. 004 Post-Test Front View; Rear: No. 018 Post-Test Rear View; Left side: No. 006 Post-Test Left Side View; Right side: No. 008 Post-Test Right Side View; Damage close-up: No. 014 Post-Test Left Rear 3-4 View; Odometer / VIN: No. 001 Vehicle Certification Placard
- `fnol-set-equinox-side-pole`: Front: No. 004 Post-Test Frontal View of Test Vehicle; Rear: No. 012 Post-Test Rear View of Test Vehicle; Left side: No. 008 Post-Test Left Side View of Test Vehicle; Right side: No. 014 Post-Test Right Side View of Test Vehicle; Damage close-up: No. 006 Post-Test Left Front 3-4 View of Test Vehicle; Odometer / VIN: No. 055 Close-Up View of Vehicle Certification Label
- `fnol-set-altima-offset-frontal`: Front: No. 06 Post-Test Front View of Test Vehicle; Rear: No. 14 Post-Test Rear View of Test Vehicle; Left side: No. 10 Post-Test Left Side View of Test Vehicle; Right side: No. 18 Post-Test Right Side View of Test Vehicle; Damage close-up: No. 08 Post-Test Left Front 3-4 View of Test Vehicle; Odometer / VIN: No. 03 Test Vehicle Certification Label
- `fnol-set-f150-frontal`: Front: No. 009 Post-Test Front View of Test Vehicle; Rear: No. 017 Post-Test Left Rear 3-4 View; Left side: No. 011 Post-Test Left View of Test Vehicle; Right side: No. 013 Post-Test Right View of Test Vehicle; Damage close-up: No. 015 Post-Test Right Front 3-4 View; Odometer / VIN: No. 004 Manufacturer's Label

All are high-speed laboratory crashes, so expect severe assessments; they do not cover minor damage.

## Home (property) photo sets

One folder per cause of loss, filed on the home policy `POL-HOME-334455`: `overview.jpg`, `close-up.jpg` and, where
there is one, `source.jpg` (the source of the damage). In the claim form, **Load real photo set → Homes** fills the
slots. All photos are public domain (U.S. government works or CC0). Resized to at most 1280 px and re-encoded, which
also removes camera metadata. Where a set has only one original photo, `close-up.jpg` is a crop of it (noted below), so
the close-up adds no information the overview does not already have.

| Folder | Cause of loss | Photos | Source | License |
|---|---|---|---|---|
| `fnol-set-home-burst-pipe/` | water from a burst pipe | overview: [Rohrbruch 004](https://commons.wikimedia.org/wiki/File:Rohrbruch_004.jpg); close-up: [Rohrbruch 006](https://commons.wikimedia.org/wiki/File:Rohrbruch_006.jpg); source: [Rohrbruch 001](https://commons.wikimedia.org/wiki/File:Rohrbruch_001.jpg) (wall opened after a pipe burst, 2011) | Wikimedia Commons, by Pictureme | CC0 1.0 |
| `fnol-set-home-tree-on-roof/` | falling trees (wind) | [FEMA 37865](https://commons.wikimedia.org/wiki/File:FEMA_-_37865_-_Two_trees_hit_house_in_Baton_Rouge.jpg), two trees fell on a home after Hurricane Gustav, Baton Rouge, LA, 2 Sep 2008; close-up is a crop | FEMA / Barry Bahler | Public domain (U.S. government work) |
| `fnol-set-home-hurricane-roof/` | windstorm | [FEMA 259](https://commons.wikimedia.org/wiki/File:FEMA_-_259_-_Photograph_by_FEMA_News_Photo_taken_on_09-01-1992_in_Hawaii.jpg), roof blown off and top storey collapsed, Hurricane Iniki, Hawaii, 1 Sep 1992; close-up is a crop | FEMA News Photo | Public domain (U.S. government work) |
| `fnol-set-home-wildfire/` | wildfire (total loss) | overview: [FEMA 37387](https://commons.wikimedia.org/wiki/File:FEMA_-_37387_-_FEMA_PDA_Team_assesses_Wildfire_Damage_in_California.jpg); close-up: [FEMA 37385](https://commons.wikimedia.org/wiki/File:FEMA_-_37385_-_FEMA_PDA_Team_assesses_Wildfire_Damage_in_California.jpg); a damage-assessment team at a home destroyed by wildfire, Big Sur, CA, 23 Jul 2008 | FEMA / Casey Deshong | Public domain (U.S. government work) |
| `fnol-set-home-river-flood/` | flood (rising water) | [FEMA 34626](https://commons.wikimedia.org/wiki/File:FEMA_-_34626_-_Home_surrounded_by_water_in_Arkansas.jpg), home surrounded by flood water, Des Arc, AR, 25 Mar 2008; close-up is a crop | FEMA / Jocelyn Augustino | Public domain (U.S. government work) |

The claim descriptions in the app are invented for the demo; they are not statements by the people or owners of the
places shown. Flood is excluded from standard homeowners policies, so the flood set is expected to raise
`POSSIBLE_FLOOD_EXCLUSION` and go to an adjuster.
