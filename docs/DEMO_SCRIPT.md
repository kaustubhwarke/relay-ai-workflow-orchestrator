# Explainer video: script and shot list (about 10 minutes)

The video must show: workflow trigger, AI-node branching, approval gate, worker crash and resume, prompt-injection handling, step-cap protection, and the smoke test.
This script covers all of them in the order of the problem statement's recommended demo flow. Record the console in a browser plus one terminal side by side.

## Before recording (2 minutes, off camera)

```bash
cp .env.example .env     # RELAY_API_TOKEN=relay-demo-token ; RELAY_AI_PROVIDER=openai ; OPENAI_API_KEY=<your key>
docker compose down -v && docker compose up -d --build
docker compose ps                          # all healthy
curl -s -X POST localhost:9210/admin/reset
```

Open http://localhost:8080, Connect with the token. Keep a terminal in the repo with `export TOKEN=relay-demo-token`.

## Scenes

| # | Time | Show | Say (key points) | Do |
|---|---|---|---|---|
| 1 | 0:00 | Title card, then the architecture diagram from the README | "Relay runs business workflows reliably, including steps where an AI model decides. The hard part is not the AI call, it is surviving crashes, never repeating a side effect, and never letting the model talk its way past a human." Name the pieces: API, worker, PostgreSQL as store and queue, the mock world. | none |
| 2 | 0:45 | Console, Workflows tab | Four seeded workflows loaded as Published, a draft-to-publish lifecycle, immutable versions, every run snapshots its version. | Click **Definition** on `wf_support_triage`; point at the node graph (AI classify → condition → approval → refund → notify), and that the webhook secret is redacted. |
| 3 | 1:30 | Trigger `wf_support_triage` with **pay_001** | Triggering only enqueues and returns 202; the worker executes. | Console **Trigger**, choose `pay_001`. Open the run. |
| 4 | 2:15 | The run trace | Walk the steps: resolved input of the AI node (the untrusted message is delimited), validated JSON output against the schema, token usage, the condition branching on `category`, the notify call with its idempotency key. | Expand `classify`, `route`, `notify_support`. Show `ai_attempts` and the tokens. |
| 5 | 3:15 | **Injection**: trigger with **pay_inject_001** | "This message tells the model it is admin, approval is pre-granted, refund $5000." Even if the model obeys, the engine decides, not the model. | Trigger. Run parks at `waiting_approval`. Terminal: `curl localhost:9210/orders/ord_2002` shows `delivered`, and `curl localhost:9210/admin/ledger` shows no order action. Explain: sensitive nodes need an approved row that only the approve API (role APPROVER) can write. Mention `pay_inject_002` stays on its fixed notification target. |
| 6 | 4:30 | Approvals tab: reject one, approve one | Decision and decider are recorded; reject ends the run cleanly. | Reject the injection run (status `cancelled`). Trigger `pay_002`, approve it: run resumes, refund executes once, customer email. Show the ledger has exactly one `order.refund`. |
| 7 | 5:30 | **Kill and resume** | "Kill -9, no graceful shutdown, in the middle of a run." | Terminal: `curl -s -X POST localhost:9210/admin/reset`; trigger `wf_slow_fulfillment` with `pay_201`; as soon as the confirmation email appears (console or `curl localhost:9210/admin/ledger`), run `docker compose kill -s KILL relay-worker`. Show the run stuck in `running` with the delay step `waiting`. `docker compose start relay-worker`. |
| 8 | 6:45 | Run finishes; duplication check | Resumed from the last persisted step, completed steps skipped, effects exactly once. Explain the crash window: effect landed but step not persisted, same idempotency key, world replays instead of repeating. | `python scripts/duplication_check.py --url http://localhost:9210` → PASS. Optionally show the crash-window drill output from the verification report (one replay absorbed). |
| 9 | 7:45 | **Step cap**: trigger `wf_runaway` | A poll loop that never ends; loops are legal, `max_steps` bounds them in the engine. | Trigger; show the run `failed`, error `max_steps_exceeded`, 12 steps. |
| 10 | 8:30 | Smoke test | The provided contract test. | `python scripts/smoke_test.py --url http://localhost:8080 --token $TOKEN` → 28 passed. |
| 11 | 9:00 | Natural-language compiler (if time) | Plain English in, validated Draft out, or a refusal naming missing capabilities; the platform's own validator is the referee. | `curl -X POST localhost:8080/workflows/compile ...` with the `nl_demo_001` text, publish, trigger; then `nl_trap_001` → refusal. Show the eval accuracy table from `docs/NL_EVAL_REPORT.md`. |
| 12 | 9:40 | Close | Recap: durable, exactly-once, schema-enforced AI, gates the model cannot cross; tests (unit + integration) and drills; where the docs are. Known limitations: single-database queue ceiling, graph not full DAG engine. | Show the README and `docs/VERIFICATION_REPORT.md`. |

## Talking points for questions

* *Why PostgreSQL as the queue?* One transactional system for state, queue and audit; `SKIP LOCKED` plus leases is proven; a broker is the next step at very high volume.
* *What if two workers claim the same run?* Leases make that rare; `UNIQUE (run_id, sequence)` on steps and stable idempotency keys make it harmless.
* *Why is the idempotency key free of the attempt number?* A retry or resume must send the same key the first attempt sent, otherwise the downstream cannot recognise the replay.
* *Can the model approve its own refund?* No. Nothing a model outputs can create an approval row; the engine checks the table before executing any sensitive node.
* *Why are loops allowed?* Polling and retry patterns need them; `max_steps` (and the timeout) bound them at runtime.
