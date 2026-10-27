"""Run Gemini on the sampled images exactly as the app calls it, and record the answers.

The prompt is read from GeminiVisionProvider.java and the request matches the app (the photo is
labelled "Photo 1 — angle: OTHER", as the app labels a photo of unknown angle; JSON output,
temperature 0, thinking budget), so results reflect production behaviour. Each record carries the
prompt's SHA-256, and a run never mixes prompts: after the prompt changes, start a new --run.
The API key comes from the GEMINI_API_KEY environment variable or the repo's .env file; it is
sent only to Google and never printed or written to disk.

Usage:  python -P evaluate.py --run thinking0 --thinking-budget 0 --limit 60 --max-calls 15
Writes: results/<run>.jsonl (one line per image). Re-running resumes: images already answered are
skipped and images that failed are retried. When Google's daily quota is used up the run stops
cleanly, so a free-tier key can finish the sample over several days (run it once a day).
The app shares the key's quota: use --max-calls to leave some calls for the app.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import textwrap
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DATA = os.path.join(HERE, "data")
RESULTS = os.path.join(HERE, "results")
PROVIDER = os.path.join(REPO, "fnol-agents", "src", "main", "java", "com", "guidewire", "fnol", "agents",
                        "GeminiVisionProvider.java")
URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


def app_prompt():
    src = open(PROVIDER, encoding="utf-8").read().replace("\r\n", "\n")
    block = re.search(r'DAMAGE_ASSESSMENT_PROMPT\s*=\s*"""\n(.*?)"""', src, re.S).group(1)
    return textwrap.dedent(block)   # Java text blocks strip the common indentation the same way


def api_key():
    if os.environ.get("GEMINI_API_KEY"):
        return os.environ["GEMINI_API_KEY"]
    with open(os.path.join(REPO, ".env"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("GEMINI_API_KEY not set and not found in .env")


def prompt_sha(prompt):
    return hashlib.sha256(prompt.encode("utf-8")).hexdigest()[:16]


def call(key, model, prompt, image_bytes, mime, budget, timeout, angle="OTHER"):
    config = {"responseMimeType": "application/json", "temperature": 0}
    if "2.5" in model:
        config["thinkingConfig"] = {"thinkingBudget": budget}
    body = {"contents": [{"parts": [{"text": prompt},
                                    {"text": f"Photo 1 — angle: {angle}"},
                                    {"inline_data": {"mime_type": mime,
                                                     "data": base64.b64encode(image_bytes).decode()}}]}],
            "generationConfig": config}
    req = urllib.request.Request(URL.format(model=model), json.dumps(body).encode(),
                                 {"Content-Type": "application/json", "x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


class DailyQuotaExhausted(Exception):
    pass


def quota_violation(body):
    """The quota id from a 429 body, e.g. GenerateRequestsPerDayPerProjectPerModel-FreeTier."""
    try:
        for detail in json.loads(body)["error"].get("details", []):
            for v in detail.get("violations", []):
                return v.get("quotaId", "")
    except (ValueError, KeyError):
        pass
    return ""


def parse(resp):
    text = resp["candidates"][0]["content"]["parts"][0]["text"].strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-z]*\n?|\n?```$", "", text).strip()
    return json.loads(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="name of the results file")
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--thinking-budget", type=int, default=0)
    ap.add_argument("--rpm", type=float, default=8, help="requests per minute (stay under your quota)")
    ap.add_argument("--timeout", type=int, default=60)
    ap.add_argument("--limit", type=int, default=0, help="only the first N sample images (0 = all)")
    ap.add_argument("--max-calls", type=int, default=0, help="stop after N API calls this run (0 = no cap)")
    args = ap.parse_args()

    key, prompt = api_key(), app_prompt()
    labels = {}
    with open(os.path.join(DATA, "labels.jsonl"), encoding="utf-8") as f:
        for line in f:
            lab = json.loads(line)
            labels[lab["image"]] = lab
    sample = [s for s in open(os.path.join(DATA, "sample.txt"), encoding="utf-8").read().split("\n") if s]
    if args.limit:
        sample = sample[:args.limit]

    os.makedirs(RESULTS, exist_ok=True)
    out_path = os.path.join(RESULTS, f"{args.run}.jsonl")
    # Keep only answered images; failed ones are dropped so this run retries them.
    kept = []
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            kept = [json.loads(l) for l in f if l.strip()]
        kept = [r for r in kept if "prediction" in r]
        other = {r.get("promptSha256", "unrecorded") for r in kept} - {prompt_sha(prompt)}
        if other:
            raise SystemExit(f"results/{args.run}.jsonl was made with a different prompt ({', '.join(sorted(other))}); "
                             "the app's prompt has changed since. Use a new --run name.")
        with open(out_path, "w", encoding="utf-8") as f:
            f.writelines(json.dumps(r) + "\n" for r in kept)
    done = {r["image"] for r in kept}

    gap = 60.0 / args.rpm
    todo = [s for s in sample if s not in done]
    if args.max_calls:
        todo = todo[:args.max_calls]
    print(f"{len(done)} done, {len(todo)} to do this run, ~{len(todo) * gap / 60:.0f} min at {args.rpm} rpm")
    try:
        run(todo, labels, key, prompt, args, out_path, gap)
    except DailyQuotaExhausted as e:
        print(f"Stopped: daily quota used up ({e}). Run again tomorrow to continue; nothing was lost.")
    answered = sum(1 for l in open(out_path, encoding="utf-8") if l.strip()) if os.path.exists(out_path) else 0
    print(f"{answered}/{len(sample)} images answered so far.")


def run(todo, labels, key, prompt, args, out_path, gap):
    for i, name in enumerate(todo, 1):
        path = os.path.join(DATA, "hitl", labels[name]["path"])
        mime = "image/png" if name.lower().endswith(".png") else "image/jpeg"
        record = {"image": name, "model": args.model, "thinkingBudget": args.thinking_budget,
                  "promptSha256": prompt_sha(prompt)}
        started = time.time()
        for attempt in range(4):
            try:
                resp = call(key, args.model, prompt, open(path, "rb").read(), mime, args.thinking_budget, args.timeout)
                record.update(prediction=parse(resp), latency=round(time.time() - started, 2),
                              usage=resp.get("usageMetadata", {}))
                break
            except urllib.error.HTTPError as e:
                body = e.read().decode(errors="replace")
                if e.code == 429 and "PerDay" in quota_violation(body):
                    raise DailyQuotaExhausted(quota_violation(body))
                if e.code in (429, 500, 503) and attempt < 3:
                    wait = 30 * (attempt + 1)
                    print(f"  HTTP {e.code} on {name}; retrying in {wait}s")
                    time.sleep(wait)
                    continue
                record.update(error=f"HTTP {e.code}: {body[:300]}")
                break
            except Exception as e:   # timeouts, malformed JSON
                record.update(error=f"{type(e).__name__}: {str(e)[:200]}", latency=round(time.time() - started, 2))
                break
        with open(out_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(record) + "\n")
        status = record.get("error") or f"{record['prediction'].get('damageType')} " \
                                           f"conf={record['prediction'].get('confidence')} {record['latency']}s"
        print(f"[{i}/{len(todo)}] {name}: {status}", flush=True)
        time.sleep(max(0.0, gap - (time.time() - started)))


if __name__ == "__main__":
    main()
