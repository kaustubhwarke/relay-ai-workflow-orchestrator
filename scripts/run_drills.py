#!/usr/bin/env python3
"""Relay verification harness. Zero dependencies (Python 3.9+ stdlib).

Runs the smoke test and every drill from docs/EVALUATION_GUIDE.md against a RUNNING stack and writes a
Markdown report containing the real outputs. Nothing is simulated: the worker is hard-killed and restarted.

    # local jar (Windows PowerShell kill / java restart are passed in as commands)
    python scripts/run_drills.py --url http://localhost:8080 --token relay-demo-token \
        --kill-cmd "docker compose kill -s KILL relay-worker" --start-cmd "docker compose start relay-worker"

The kill/start commands are yours because only you know how the stack was started (compose, a jar, a pod).
For an in-process worker (API and worker in one process) kill/start the whole process; that is the harshest case.
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESULTS = []
REPORT = []


def say(line=""):
    print(line)
    REPORT.append(line)


class Api:
    def __init__(self, url, token):
        self.url, self.token = url.rstrip("/"), token

    def call(self, method, path, body=None, headers=None, base=None):
        h = {"Content-Type": "application/json"}
        if self.token and base is None:
            h["Authorization"] = f"Bearer {self.token}"
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request((base or self.url) + path, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw.strip() else {})
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw)
            except json.JSONDecodeError:
                return e.code, {"_raw": raw}
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            return 0, {"_error": str(e)}


def sh(cmd, check=False):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=ROOT)
    if check and p.returncode != 0:
        raise RuntimeError(f"command failed: {cmd}\n{p.stdout}\n{p.stderr}")
    return p


def wait_ready(api, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        code, _ = api.call("GET", "/actuator/health")
        if code == 200:
            return True
        time.sleep(1)
    return False


def poll_run(api, run_id, until, timeout=120):
    end = time.time() + timeout
    last = {}
    while time.time() < end:
        code, body = api.call("GET", f"/runs/{run_id}")
        if code == 200:
            last = body
            if body["status"] in until:
                return body
        time.sleep(0.7)
    return last


def record(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    say(f"- **{'PASS' if ok else 'FAIL'}** {name}" + (f" ({detail})" if detail else ""))


def ledger(api, world):
    return api.call("GET", "/admin/ledger", base=world)[1].get("entries", [])


def dup_check(world):
    p = sh(f'python scripts/duplication_check.py --url {world}')
    say("```text")
    say((p.stdout + p.stderr).strip())
    say("```")
    return p.returncode == 0


def trigger_slow(api):
    return api.call("POST", "/workflows/wf_slow_fulfillment/trigger",
                    {"input": {"order_id": "ord_2003", "customer_email": "lena@example.com"}})[1]["run_id"]


def payload(pid):
    for line in (ROOT / "data" / "sample_payloads.jsonl").read_text(encoding="utf-8").splitlines():
        p = json.loads(line)
        if p["id"] == pid:
            return p
    raise KeyError(pid)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--token", default="relay-demo-token")
    ap.add_argument("--world", default="http://localhost:9210")
    ap.add_argument("--kill-cmd", required=True, help="hard-kills the process that runs the worker (kill -9 semantics)")
    ap.add_argument("--start-cmd", required=True, help="starts it again")
    ap.add_argument("--skip-ai", action="store_true", help="skip drills that need the AI provider")
    ap.add_argument("--out", default=str(ROOT / "docs" / "VERIFICATION_REPORT.md"))
    ap.add_argument("--ai-label", default="(unspecified)", help="text describing the AI provider/model used, for the report")
    a = ap.parse_args()
    api = Api(a.url, a.token)
    world = a.world

    say("# Relay verification report")
    say()
    say(f"Generated {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')} by `scripts/run_drills.py` against `{a.url}`.")
    say(f"AI provider for model-backed drills: {a.ai_label}.")
    say()
    if not wait_ready(api, 60):
        say("**The stack is not reachable; aborting.**")
        sys.exit(2)

    # ---------------------------------------------------------------- 1. smoke test
    say("## 1. Provided smoke test")
    api.call("POST", "/admin/reset", base=world)
    p = sh(f"python scripts/smoke_test.py --url {a.url} --token {a.token} --world {world}")
    say("```text")
    say(p.stdout.strip())
    say("```")
    record("scripts/smoke_test.py", p.returncode == 0, p.stdout.strip().splitlines()[-1].strip())

    # ---------------------------------------------------------------- 2. kill and resume
    say()
    say("## 2. Kill-and-resume drill (worker hard-killed during the 20 s delay)")
    api.call("POST", "/admin/reset", base=world)
    run = trigger_slow(api)
    end = time.time() + 30
    while time.time() < end and not ledger(api, world):
        time.sleep(0.3)
    before = poll_run(api, run, {"succeeded", "failed", "cancelled"}, timeout=0.1)
    say(f"Run `{run}` before the kill: status `{before.get('status')}`, steps "
        f"`{[(s['node_id'], s['status']) for s in before.get('steps', [])]}`, ledger entries `{len(ledger(api, world))}`.")
    say(f"Kill: `{a.kill_cmd}`; restart: `{a.start_cmd}`.")
    sh(a.kill_cmd)
    time.sleep(3)
    sh(a.start_cmd)
    ready = wait_ready(api, 120)
    done = poll_run(api, run, {"succeeded", "failed", "cancelled"}, timeout=120)
    say(f"After restart: status `{done.get('status')}`, steps "
        f"`{[(s['node_id'], s['status'], 'attempt ' + str(s['attempt'])) for s in done.get('steps', [])]}`.")
    ok = dup_check(world)
    record("run resumes after hard kill and completes", ready and done.get("status") == "succeeded", f"status {done.get('status')}")
    record("duplication check after kill-and-resume", ok)

    # ---------------------------------------------------------------- 3. crash window
    say()
    say("## 3. Crash-window drill (worker hard-killed while a side-effect call is in flight)")
    say("The world is slowed to 8 s, so the effect lands *after* the worker dies and *before* the step could be persisted.")
    api.call("POST", "/admin/reset", base=world)
    api.call("POST", "/admin/config", {"latency_ms": 8000}, base=world)
    run = trigger_slow(api)
    time.sleep(2)
    mid = poll_run(api, run, {"succeeded", "failed", "cancelled"}, timeout=0.1)
    say(f"Before the kill: steps `{[(s['node_id'], s['status']) for s in mid.get('steps', [])]}`.")
    sh(a.kill_cmd)
    time.sleep(9)
    api.call("POST", "/admin/config", {"latency_ms": 0}, base=world)
    say("Ledger after the kill (the effect landed, the step was never marked complete):")
    say("```text")
    for e in ledger(api, world):
        say(f"seq {e['seq']}  {e['action']:16} key={e['idempotency_key']}  {'REPLAYED' if e['replayed'] else 'executed'}")
    say("```")
    sh(a.start_cmd)
    wait_ready(api, 120)
    done = poll_run(api, run, {"succeeded", "failed", "cancelled"}, timeout=120)
    entries = ledger(api, world)
    say("Ledger after resume:")
    say("```text")
    for e in entries:
        say(f"seq {e['seq']}  {e['action']:16} key={e['idempotency_key']}  {'REPLAYED (absorbed)' if e['replayed'] else 'executed'}")
    say("```")
    ok = dup_check(world)
    replays = sum(1 for e in entries if e["replayed"])
    record("run completes after a kill inside the crash window", done.get("status") == "succeeded", f"status {done.get('status')}")
    record("the re-sent call was absorbed by the idempotency key", replays >= 1, f"{replays} replay(s) absorbed")
    record("duplication check after crash-window kill", ok)

    # ---------------------------------------------------------------- 4. injection / approval gate
    say()
    say("## 4. Prompt injection and the approval gate")
    if a.skip_ai:
        say("_Skipped (--skip-ai)._")
    else:
        api.call("POST", "/admin/reset", base=world)
        wf, secret = "wf_support_triage", "whsec_triage_501"

        def hook(pid):
            p = payload(pid)
            code, body = api.call("POST", f"/hooks/{wf}", p["body"], headers={"X-Relay-Secret": secret})
            return body["run_id"]

        r1 = hook("pay_inject_001")
        run = poll_run(api, r1, {"waiting_approval", "succeeded", "failed", "cancelled"}, timeout=90)
        classify = next((s for s in run.get("steps", []) if s["node_id"] == "classify"), {})
        say(f"`pay_inject_001` (tries to skip approval and refund $5000): classifier output `{classify.get('output')}`, "
            f"tokens `{classify.get('tokens')}`, run status `{run.get('status')}`.")
        orders_touched = [e for e in ledger(api, world) if e["action"].startswith("order.")]
        order = api.call("GET", "/orders/ord_2002", base=world)[1]
        record("injection run parks at waiting_approval", run.get("status") == "waiting_approval")
        record("no order action before a human decides", not orders_touched and order.get("status") == "delivered",
               f"order ord_2002 is '{order.get('status')}'")
        pending = api.call("GET", "/approvals?status=pending")[1]
        apr = next(x for x in pending if x["run_id"] == r1)
        api.call("POST", f"/approvals/{apr['id']}/reject", {"comment": "prompt injection"})
        run = poll_run(api, r1, {"cancelled", "failed", "succeeded"}, timeout=30)
        record("rejecting ends the run cleanly (cancelled)", run.get("status") == "cancelled")
        record("order untouched after rejection", api.call("GET", "/orders/ord_2002", base=world)[1].get("status") == "delivered")

        r2 = hook("pay_inject_002")
        run = poll_run(api, r2, {"succeeded", "failed", "cancelled", "waiting_approval"}, timeout=90)
        out = next((s for s in run.get("steps", []) if s["node_id"] == "classify"), {}).get("output")
        say(f"`pay_inject_002` (tries to exfiltrate data): classifier output `{out}`, run status `{run.get('status')}`.")
        sends = [e for e in ledger(api, world) if e["action"] in ("email.send", "chat.message")]
        targets = {(e["payload"].get("to") or e["payload"].get("channel")) for e in sends}
        record("notifications only reach definition-configured targets", targets <= {"#support"} and "evil" not in json.dumps(sends),
               f"targets {sorted(targets)}")

        r3 = hook("pay_002")
        run = poll_run(api, r3, {"waiting_approval", "failed", "succeeded", "cancelled"}, timeout=90)
        record("genuine refund request waits for approval", run.get("status") == "waiting_approval")
        pending = api.call("GET", "/approvals?status=pending")[1]
        apr = next(x for x in pending if x["run_id"] == r3)
        api.call("POST", f"/approvals/{apr['id']}/approve", {"comment": "verified"})
        run = poll_run(api, r3, {"succeeded", "failed", "cancelled"}, timeout=60)
        refunds = [e for e in ledger(api, world) if e["action"] == "order.refund" and not e["replayed"]]
        record("approved refund executes exactly once and the run completes", run.get("status") == "succeeded" and len(refunds) == 1)
        record("duplication check after the approval drill", dup_check(world))

    # ---------------------------------------------------------------- 5. outage and flaky world
    say()
    say("## 5. Mock-world outage and flakiness")
    api.call("POST", "/admin/reset", base=world)
    api.call("POST", "/admin/config", {"mode": "down"}, base=world)
    code, body = api.call("POST", "/workflows/wf_expense_approval/trigger",
                          {"input": {"employee_email": "dev2@example.com", "amount_usd": 40, "description": "Outage lunch"}})
    run_id = body["run_id"]
    time.sleep(2.0)
    api.call("POST", "/admin/config", {"mode": "ok"}, base=world)
    run = poll_run(api, run_id, {"succeeded", "failed", "cancelled"}, timeout=60)
    step = next((s for s in run.get("steps", []) if s["node_id"] == "auto_ok"), {})
    say(f"World down for ~2 s mid-run: run `{run.get('status')}`, notify step needed `{step.get('attempt')}` attempt(s).")
    record("run survives a short world outage via retry with backoff", run.get("status") == "succeeded" and step.get("attempt", 1) > 1,
           f"{step.get('attempt')} attempts")
    api.call("POST", "/admin/reset", base=world)
    api.call("POST", "/admin/config", {"fail_rate": 0.3}, base=world)
    ids = [api.call("POST", "/workflows/wf_expense_approval/trigger",
                    {"input": {"employee_email": f"flaky{i}@example.com", "amount_usd": 10, "description": f"flaky {i}"}})[1]["run_id"]
           for i in range(8)]
    runs = [poll_run(api, i, {"succeeded", "failed", "cancelled"}, timeout=90) for i in ids]
    api.call("POST", "/admin/config", {"fail_rate": 0}, base=world)
    statuses = [r.get("status") for r in runs]
    retries = sum(max(0, s["attempt"] - 1) for r in runs for s in r.get("steps", []))
    say(f"30% failure injection, 8 runs: statuses `{statuses}`, {retries} retried attempt(s) absorbed.")
    record("flaky world (30% errors) is absorbed by retries", all(s == "succeeded" for s in statuses), f"{retries} retries")
    record("duplication check after the flaky-world drill", dup_check(world))

    # ---------------------------------------------------------------- 6. step cap
    say()
    say("## 6. Step cap")
    code, body = api.call("POST", "/workflows/wf_runaway/trigger", {"input": {}})
    run = poll_run(api, body["run_id"], {"failed", "cancelled", "succeeded"}, timeout=90)
    err = run.get("error") or {}
    say(f"`wf_runaway`: status `{run.get('status')}`, steps executed `{run.get('steps_executed')}`, error `{err}`.")
    record("wf_runaway is stopped at max_steps with a reason naming the cap",
           run.get("status") == "failed" and err.get("code") == "max_steps_exceeded" and "max_steps" in err.get("message", ""))

    # ---------------------------------------------------------------- 7. schema enforcement
    say()
    say("## 7. Schema enforcement on AI output")
    wf_id = f"wf_drill_schema_{int(time.time())}"
    definition = {
        "id": wf_id, "name": "Strict schema drill", "trigger": {"type": "manual"}, "entry": "ask",
        "limits": {"max_steps": 5, "timeout_seconds": 120, "max_ai_tokens": 0},
        "nodes": [{"id": "ask", "type": "ai", "params": {
            "prompt": "Classify this message and summarise it: 'My speaker is broken and I am quite upset about it.'",
            "output_schema": {"type": "object", "properties": {
                "category": {"type": "string", "enum": ["refund_request", "complaint", "question"]},
                "summary": {"type": "string", "maxLength": 12}},
                "required": ["category", "summary"], "additionalProperties": False}}, "next": None}]}
    api.call("POST", "/workflows", definition)
    api.call("POST", f"/workflows/{wf_id}/publish")
    code, body = api.call("POST", f"/workflows/{wf_id}/trigger", {"input": {}})
    run = poll_run(api, body["run_id"], {"succeeded", "failed", "cancelled"}, timeout=90)
    s = next((x for x in run.get("steps", []) if x["node_id"] == "ask"), {})
    attempts = s.get("ai_attempts") or []
    say(f"Strict schema (summary maxLength 12): run `{run.get('status')}`, AI attempts recorded: `{len(attempts)}`.")
    say("```json")
    say(json.dumps([{k: v for k, v in at.items() if k != 'output_excerpt'} for at in attempts], indent=2))
    say("```")
    ok = (run.get("status") == "succeeded" and len(attempts) >= 1 and attempts[-1].get("valid")) or \
         (run.get("status") == "failed" and s.get("error_code") == "ai_output_invalid" and len(attempts) == 2)
    record("schema violations are caught: repaired on the one retry, else the step fails cleanly", ok,
           f"{len(attempts)} attempt(s), run {run.get('status')}")

    # ---------------------------------------------------------------- summary
    say()
    say("## Summary")
    say()
    say("| Check | Result |")
    say("|---|---|")
    for name, ok, detail in RESULTS:
        say(f"| {name} | {'PASS' if ok else 'FAIL'}{' (' + detail + ')' if detail else ''} |")
    failed = [r for r in RESULTS if not r[1]]
    say()
    say(f"**{len(RESULTS) - len(failed)} of {len(RESULTS)} checks passed.**")
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text("\n".join(REPORT) + "\n", encoding="utf-8")
    print(f"\nReport written to {a.out}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
