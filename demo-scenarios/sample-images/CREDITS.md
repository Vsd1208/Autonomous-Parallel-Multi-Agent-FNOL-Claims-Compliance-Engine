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
