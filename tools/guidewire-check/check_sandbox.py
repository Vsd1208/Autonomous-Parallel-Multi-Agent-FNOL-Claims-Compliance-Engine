"""
First-day sandbox check: what does a real tenant say to the calls this project assumes?

Never run against a real tenant so far. Run it on a NON-PRODUCTION sandbox only. Read-only by default; --write also creates
one draft claim and submits it, which cannot be undone (a submitted claim can only be closed).

    set GW_BASE_URL=https://...        (the tenant's Cloud API root)
    set GW_TOKEN_URL=https://...
    set GW_CLIENT_ID=...
    set GW_CLIENT_SECRET=...
    python check_sandbox.py [--claim-id ID] [--write --policy NUMBER --loss-date YYYY-MM-DD]

Prints what happened for each assumption. It never prints the credentials or tokens, and saves nothing.
"""
import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def call(method, url, token=None, body=None, form=None, basic=None):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if basic:
        headers["Authorization"] = "Basic " + base64.b64encode(basic.encode()).decode()
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = urllib.parse.urlencode(form).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, None
    except OSError as e:
        return 0, {"error": type(e).__name__}


def report(what, code, answer, keys=None):
    print(f"[{'ok ' if 200 <= code < 300 else 'NO '}] {what}: HTTP {code}")
    if not 200 <= code < 300 and isinstance(answer, dict):
        print("      answer keys:", sorted(answer.keys()))
    if keys:
        print("      attributes present:", sorted(keys))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--claim-id", help="an existing claim's id, to read")
    ap.add_argument("--write", action="store_true", help="create and submit one draft claim (cannot be undone)")
    ap.add_argument("--policy")
    ap.add_argument("--loss-date")
    args = ap.parse_args()
    env = {k: os.environ.get(k, "") for k in ("GW_BASE_URL", "GW_TOKEN_URL", "GW_CLIENT_ID", "GW_CLIENT_SECRET")}
    missing = [k for k, v in env.items() if not v]
    if missing:
        sys.exit("Set " + ", ".join(missing))
    if args.write and not (args.policy and args.loss_date):
        sys.exit("--write needs --policy and --loss-date")

    print("Assumption: OAuth2 client credentials")
    code, answer = call("POST", env["GW_TOKEN_URL"], form={"grant_type": "client_credentials"},
                        basic=env["GW_CLIENT_ID"] + ":" + env["GW_CLIENT_SECRET"])
    report("token request", code, answer)
    token = (answer or {}).get("access_token") if isinstance(answer, dict) else None
    if not token:
        sys.exit("No access_token: fix this first; everything else depends on it.")

    base = env["GW_BASE_URL"].rstrip("/")
    print("\nAssumption: the Claim API is at /claim/v1")
    code, answer = call("GET", base + "/claim/v1/claims?pageSize=1", token)
    report("list claims", code, answer)

    if args.claim_id:
        print("\nAssumption: data.attributes envelope, with id, claimNumber, state")
        code, answer = call("GET", f"{base}/claim/v1/claims/{urllib.parse.quote(args.claim_id)}", token)
        a = (answer or {}).get("data", {}).get("attributes", {}) if isinstance(answer, dict) else {}
        report("read a claim", code, answer, a.keys())

    if args.policy:
        print("\nDocumented path, assumed fields: policy search (status, dates, product, jurisdiction, cancellation, endorsements)")
        search = {"data": {"attributes": {"policyNumber": args.policy}}}
        code, answer = call("POST", base + "/policy/v1/search/policies", token, body=search)
        found = (answer or {}).get("data", []) if isinstance(answer, dict) else []
        report("search for the policy", code, answer, found[0].get("attributes", {}).keys() if found else None)
        print("\nDocumented path, assumed fields: claim search by policy number (the claim history)")
        code, answer = call("POST", base + "/claim/v1/search/claims-v2", token, body=search)
        found = (answer or {}).get("data", []) if isinstance(answer, dict) else []
        report("search claims on the policy", code, answer, found[0].get("attributes", {}).keys() if found else None)
        print("      Not checked: a coverages path. None was found in the public documentation; read the tenant's swagger.json and set guidewire.cloud.coverages-path.")

    if args.write:
        print("\nAssumption: draft, then submit (this creates a real claim in the sandbox)")
        draft = {"data": {"attributes": {"policyNumber": args.policy, "lossDate": args.loss_date + "T00:00:00.000Z"}}}
        code, answer = call("POST", base + "/claim/v1/claims", token, body=draft)
        a = (answer or {}).get("data", {}).get("attributes", {}) if isinstance(answer, dict) else {}
        report("create a draft", code, answer, a.keys())
        print("      draft number:", a.get("claimNumber"), "| id:", a.get("id"))
        if a.get("id"):
            sub = f"{base}/claim/v1/claims/{urllib.parse.quote(str(a['id']))}/submit"
            code, answer = call("POST", sub, token, body={"data": {"attributes": {}}})
            b = (answer or {}).get("data", {}).get("attributes", {}) if isinstance(answer, dict) else {}
            report("submit it", code, answer, b.keys())
            print("      open number:", b.get("claimNumber"), "| state:", b.get("state"))
            for path, what, attrs in (("activities", "activity", {"subject": "Sandbox check"}),
                                      ("notes", "note", {"body": "Sandbox check", "subject": "Sandbox check"})):
                code, answer = call("POST", f"{base}/claim/v1/claims/{urllib.parse.quote(str(a['id']))}/{path}", token,
                                    body={"data": {"attributes": attrs}})
                report(f"add {what}", code, answer)

    print("\nCompare with docs/guidewire-integration.md, and correct the document and the adapter wherever these differ.")


if __name__ == "__main__":
    main()
