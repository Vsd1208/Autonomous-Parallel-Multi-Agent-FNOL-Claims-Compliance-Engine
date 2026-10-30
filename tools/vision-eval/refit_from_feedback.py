"""Refit the trust score with adjusters' decisions added to the labelled evaluation photos.

Every adjuster decision is stored next to what the AI said (table ai_feedback). When the adjuster confirms the damage
type, that claim becomes one more labelled example from the insurer's own claims: right when the adjuster confirmed the
AI's damage type. Only decisions on answers from the same model, prompt and thinking budget as the evaluation run are
used (column vision_source), so a prompt change never mixes old answers in.

The refit goes through the same honest measurement and shipping rules as trust_score.py (nested cross-validation,
bootstrap ranges, must beat Gemini's raw confidence and must not be worse than the app's current trust score). It
writes the app file only with --write-app and only when those rules pass; rebuild the API afterwards.

Usage:  python -P refit_from_feedback.py --run v3-subject [--write-app]
        (needs the compose stack running; reads the database with psql inside the postgres container)
Writes: data/feedback.csv (gitignored: claim ids), results/report-trust-score.md
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from calibrate import REPO   # noqa: E402

EXPORT = os.path.join(HERE, "data", "feedback.csv")
QUERY = ("SELECT claim_id, line_of_business, ai_damage_type, adjuster_damage_type, ai_confidence, raw_confidence, "
         "probability_margin, second_opinion_agreement, vision_source, decided_at FROM ai_feedback ORDER BY decided_at")


def export():
    """Adjuster feedback as CSV, read through psql in the compose postgres container (no password leaves it)."""
    out = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "fnol", "-d", "fnol", "--csv",
                          "-c", QUERY], cwd=REPO, capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise SystemExit("Could not read ai_feedback (is the compose stack running?): " + out.stderr.strip())
    os.makedirs(os.path.dirname(EXPORT), exist_ok=True)
    with open(EXPORT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out.stdout)
    rows = max(0, len(out.stdout.strip().splitlines()) - 1)
    print(f"exported {rows} adjuster decisions to {EXPORT}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="evaluation run measured with the app's current prompt")
    ap.add_argument("--write-app", action="store_true")
    args = ap.parse_args()
    export()
    cmd = [sys.executable, "-P", os.path.join(HERE, "trust_score.py"), "--run", args.run, "--feedback", EXPORT]
    if args.write_app:
        cmd.append("--write-app")
    sys.exit(subprocess.run(cmd).returncode)


if __name__ == "__main__":
    main()
