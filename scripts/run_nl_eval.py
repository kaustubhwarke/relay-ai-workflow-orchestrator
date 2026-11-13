#!/usr/bin/env python3
"""Runs the NL compiler eval (data/nl_eval.jsonl) through a running Relay and writes a Markdown report.

    python scripts/run_nl_eval.py --url http://localhost:8080 --token relay-demo-token --out docs/NL_EVAL_REPORT.md

Needs the API to run with a real model (RELAY_AI_PROVIDER=openai). The compiler only ever sees each case's
`description`; the labelled assertions are scored server-side exactly as docs/EVALUATION_GUIDE.md specifies.
Zero dependencies (Python 3.9+ stdlib).
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--token", default="relay-demo-token")
    ap.add_argument("--out", default=str(ROOT / "docs" / "NL_EVAL_REPORT.md"))
    ap.add_argument("--json-out", default=None, help="also save the raw JSON response")
    ap.add_argument("--analysis", default=None, help="path of a Markdown file appended as the 'Analysis of misses' section")
    a = ap.parse_args()

    req = urllib.request.Request(a.url.rstrip("/") + "/workflows/compile/eval", data=b"{}", method="POST",
                                 headers={"Authorization": f"Bearer {a.token}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            result = json.load(r)
    except urllib.error.HTTPError as e:
        print(f"eval failed: HTTP {e.code}: {e.read().decode()[:400]}", file=sys.stderr)
        sys.exit(2)

    cases = {json.loads(l)["id"]: json.loads(l) for l in (ROOT / "data" / "nl_eval.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()}
    lines = ["# NL compiler evaluation", "",
             f"Generated {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} by `scripts/run_nl_eval.py`. "
             f"Model: `{result.get('model')}`. Scoring rules: [docs/EVALUATION_GUIDE.md](EVALUATION_GUIDE.md) section 4 "
             "(the compiler sees only each case's `description`; assertions and notes are never given to the model).", "",
             f"**{result['correct']} of {result['total']} correct (accuracy {result['accuracy']:.0%}).**", "",
             "| Case | Expected | Result | Attempts | Checks | Detail |", "|---|---|---|---|---|---|"]
    for c in result["cases"]:
        checks = ", ".join(f"{k.replace(' (informational)', '*')}: {'ok' if v else 'NO'}" for k, v in c.get("checks", {}).items())
        detail = c.get("detail") or c.get("reason") or c.get("explanation") or ""
        if c.get("missing_capabilities"):
            detail += " Missing: " + ", ".join(c["missing_capabilities"])
        if c.get("missing_node_types"):
            detail += f" Missing node types: {c['missing_node_types']}."
        lines.append(f"| `{c['id']}` | {c['expected']} | **{c['result'].upper()}** | {c.get('attempts', '')} | {checks} | {detail.strip()[:260].replace('|', '/')} |")
    lines += ["", "`*` trigger_matches is informational: the guide's pass rule does not include it.", "",
              "## Case descriptions (the only input the compiler received)", ""]
    for cid, c in cases.items():
        lines.append(f"* `{cid}` ({c['expect']}): {c['description']}")
    if a.analysis and Path(a.analysis).exists():
        lines += ["", Path(a.analysis).read_text(encoding="utf-8").strip()]
    Path(a.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    if a.json_out:
        Path(a.json_out).write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(f"{result['correct']}/{result['total']} correct; report written to {a.out}")
    for c in result["cases"]:
        if c["result"] != "pass":
            print(f"  MISS {c['id']}: {c.get('outcome')} {c.get('detail', '')[:200]} {c.get('missing_node_types', '')}")


if __name__ == "__main__":
    main()
