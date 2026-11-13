# Relay API reference

Base URL (local): `http://localhost:8080`. Interactive docs: `/swagger-ui.html` (OpenAPI at `/v3/api-docs`; disabled in the `prod` profile).
All request and response bodies are JSON with **snake_case** property names. The routes marked ✱ are the fixed contract exercised
by `scripts/smoke_test.py` (see [API_CONTRACT.md](API_CONTRACT.md)); everything else is Relay's documented extension.

## Authentication and roles

| Surface | Mechanism |
|---|---|
| Platform API | `Authorization: Bearer <token>`. A single demo token (`RELAY_API_TOKEN`) carries every role. Extra tokens under `relay.security.tokens` give real separation. |
| Webhook (`POST /hooks/{id}`) | No bearer token; the per-workflow secret in the `X-Relay-Secret` header is the credential. Stored only as a SHA-256 hash, compared in constant time. |
| Health probes | `/actuator/health/**` is public. `/actuator/prometheus` needs a token. |

| Role | May |
|---|---|
| `BUILDER` | create/update/validate/publish workflows, compile from natural language, run the compiler eval, trigger manually |
| `OPERATOR` | trigger manually, cancel runs, replay failed runs |
| `APPROVER` | approve or reject approvals (the authenticated principal is recorded as `decided_by`) |
| any valid token | all `GET` routes |

Missing/invalid token → `401 unauthorized`; valid token without the role → `403 forbidden`.

## Error shape

Every failure uses one body, with no stack traces, SQL or secrets:

```json
{"error": {"message": "Node 'n1' has unknown type 'teleport'", "code": "invalid_node_type", "details": ["..."]}}
```

| Situation | Status | `code` |
|---|---|---|
| Missing/invalid platform token | 401 | `unauthorized` |
| Role missing | 403 | `forbidden` |
| Wrong or missing webhook secret | 401 | `invalid_webhook_secret` |
| Unknown workflow / run / approval | 404 | `not_found` |
| Invalid definition (create shape, or publish validation) | 422 | `invalid_node_type`, `missing_required_param`, `dangling_reference`, `entry_not_found`, `missing_branch`, `invalid_cron`, `invalid_output_schema`, `unknown_node_reference`, ... (one distinct code and message per case) |
| Malformed JSON body | 400 | `invalid_json` |
| Workflow id already exists | 409 | `workflow_exists` |
| Trigger on a never-published workflow | 409 | `workflow_not_published` |
| Webhook to a non-webhook workflow | 409 | `not_a_webhook_workflow` |
| Approve/reject an already decided approval | 409 | `approval_already_decided` |
| Cancel a terminal run | 409 | `run_already_terminal` |
| Replay a run that is not `failed` | 409 | `run_not_failed` |
| Webhook body > 256 KiB | 413 | `payload_too_large` |
| Compiler without a real model | 503 | `compiler_requires_model` |
| Compiler could not produce a valid workflow | 422 | `compile_failed` |
| Database unavailable | 503 | `storage_unavailable` |

## Workflows

| Method and path | Purpose | Success |
|---|---|---|
| `GET /workflows` ✱ | list (`id`, `name`, `status`, `current_version`, `has_unpublished_changes`, `trigger_type`, `node_count`, timestamps) | 200 |
| `POST /workflows` ✱ | create a **Draft** from a definition in the seed shape | 201 |
| `GET /workflows/{id}` | the working copy (webhook secret shown as `[REDACTED]`) | 200 |
| `PUT /workflows/{id}` | edit the working copy. A published workflow returns to `draft` but its last published version stays runnable. Send `"secret": "[REDACTED]"` (or omit it) to keep the secret, a new value to rotate it. | 200 |
| `POST /workflows/{id}/publish` ✱ | validate against the catalog and append an immutable numbered **version**; returns `warnings` (loops, unreachable nodes, ungated sensitive nodes) | 200 / 422 |
| `GET /workflows/{id}/versions` | immutable version history | 200 |
| `POST /workflows/validate` | dry-run validation: every error and warning, nothing stored | 200 |
| `POST /workflows/{id}/trigger` ✱ | manual trigger, body `{"input": {...}}` becomes `{{trigger.body}}`; **enqueues and returns immediately** | 202 `{run_id, id, status, workflow_id, workflow_version}` |
| `POST /hooks/{id}` ✱ | webhook trigger (`X-Relay-Secret`); the body becomes `{{trigger.body}}` | 202 same shape |

### Definition shape (unchanged from `data/seed_workflows.json`)

```json
{
  "id": "wf_expense_approval", "name": "...", "description": "...",
  "trigger": {"type": "webhook", "secret": "whsec_..."},
  "entry": "is_large",
  "limits": {"max_steps": 20, "timeout_seconds": 300, "max_ai_tokens": 0},
  "nodes": [
    {"id": "is_large", "type": "condition", "params": {"left": "{{trigger.body.amount_usd}}", "op": "greater_than", "right": "100"},
     "on_true": "finance_gate", "on_false": "auto_ok"},
    {"id": "finance_gate", "type": "approval", "params": {"message": "..."}, "next": "approved_notice"},
    {"id": "approved_notice", "type": "notify", "params": {"channel": "email", "to": "...", "message": "..."}, "next": null}
  ]
}
```

Optional extension: a per-node `"retry": {"max_attempts": 5, "base_delay_ms": 200, "max_delay_ms": 5000}`.
`limits.timeout_seconds` and `limits.max_ai_tokens` are enforced (0 = unlimited); the timeout excludes time spent waiting for approvals.

### Publish validation (distinct code and message per case)

Unknown node type · missing required param · invalid param type/value · unknown param · `next`/`on_true`/`on_false` pointing to a nonexistent
node · nonexistent `entry` · condition without both branches · duplicate node ids · invalid cron · invalid `output_schema` (checked against the
JSON Schema meta-schema) · template referencing an unknown root or a nonexistent node · bad `limits` · missing webhook secret.
**Loops (backward jumps) are legal** and only produce a warning; `limits.max_steps` bounds them at runtime.

## Runs

| Method and path | Purpose |
|---|---|
| `GET /runs?workflow_id=&status=&limit=&offset=` | paged history `{items, total, limit, offset}` |
| `GET /runs/{id}` ✱ | the full trace (below) |
| `POST /runs/{id}/cancel` | `queued` → cancelled now; `waiting_approval` → cancelled and its approval closed; `running` → cooperative (checked between steps, the step in flight may finish); terminal → 409 |
| `POST /runs/{id}/replay` | re-run a **failed** run from its failed step with the original input; the failed step keeps its idempotency key |

Run statuses: `queued`, `running`, `waiting_approval`, `succeeded`, `failed`, `cancelled`.

```json
{
  "run_id": "run_9c852cecaf29", "id": "run_9c852cecaf29", "workflow_id": "wf_slow_fulfillment", "workflow_version": 1,
  "status": "succeeded", "trigger_type": "manual", "input": {"order_id": "ord_2003"},
  "steps_executed": 4, "ai_tokens_used": 0, "error": null, "cancelled_reason": null, "replay_of_run_id": null,
  "created_at": "...", "started_at": "...", "finished_at": "...",
  "steps": [{
    "sequence": 1, "node_id": "confirm", "type": "notify", "status": "succeeded", "attempt": 1,
    "input":  {"channel": "email", "to": "lena@example.com", "message": "..."},
    "output": {"delivered": true, "notification_id": "eml_..."},
    "idempotency_key": "run_9c852cecaf29:confirm", "started_at": "...", "finished_at": "...", "duration_ms": 12
  }]
}
```

AI steps additionally carry `tokens {prompt, completion}` and `ai_attempts` (one entry per model call with `valid`, schema `errors`,
token counts and a truncated output excerpt). Delay steps carry `resume_at`; approval steps carry `approval_id`.
The run's definition snapshot is never returned, and credential-like header values are redacted in step inputs.

## Approvals

| Method and path | Purpose |
|---|---|
| `GET /approvals?status=pending&run_id=` ✱ | list (`id`, `run_id`, `node_id`, `message`, `status`, `decided_by`, `decided_at`, `decision_comment`) |
| `GET /approvals/{id}` | one approval |
| `POST /approvals/{id}/approve` ✱ | body `{"comment": "..."}` optional. Records decider and time, completes the waiting step, re-queues the run |
| `POST /approvals/{id}/reject` | records the decision and ends the run `cancelled` (`cancelled_reason: approval_rejected`) |

## Natural-language compiler

| Method and path | Purpose |
|---|---|
| `POST /workflows/compile` | `{"description": "...", "persist": true}` → `{workflow, draft, explanation, warnings, webhook_secret?, attempts, model}` or `{refusal: {reason, missing_capabilities}}`. Needs a real model (`RELAY_AI_PROVIDER=openai`). The generated webhook secret is shown exactly once. |
| `POST /workflows/compile/eval` | runs `data/nl_eval.jsonl` through the compiler and returns `{model, total, correct, accuracy, cases: [...]}` |

## Reference and operations

`GET /catalog` (node and trigger registry) · `GET /stats` (runs by status, queue depth, pending approvals) ·
`GET /actuator/health` (+ `/liveness`, `/readiness`) · `GET /actuator/prometheus` · `GET /actuator/metrics`.

Prometheus series: `relay_runs_finished_total{status}`, `relay_steps_total{type,status}`, `relay_step_duration_seconds{type}`,
`relay_steps_retries_total{type}`, `relay_runs_resumed_total`, `relay_ai_tokens_total{kind}`, gauges `relay_queue_depth`,
`relay_queue_ready`, `relay_approvals_pending`.

## Step and run error codes

Step `error_code`: `template_error`, `approval_required` (engine refused a sensitive node), `dependency_unavailable`, `dependency_timeout`,
`dependency_rejected`, `dependency_bad_response`, `egress_blocked`, `invalid_url`, `invalid_param_value`, `invalid_body`, `response_too_large`,
`condition_error`, `ai_output_invalid`, `ai_provider_auth`, `ai_provider_unavailable`, `ai_provider_timeout`, `approval_rejected`, `cancelled`, `internal_error`.
Run `error.code`: any of the above plus the caps `max_steps_exceeded`, `timeout_exceeded`, `max_ai_tokens_exceeded` (these also carry `cap`), and
`node_not_found`, `max_claims_exceeded` (a poison run quarantined after too many claims).
