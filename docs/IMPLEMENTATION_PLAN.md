# Implementation plan

The plan has two tracks, both executed: **Track A** (the minimum viable submission, every Must Have) and **Track B** (production grade, Good To Have, and operability).
Order follows the problem statement's five milestones, with verification gates between phases. Each phase ends with something that runs and is tested.

## Phase map

| Phase | Goal | Track | Key outputs | Gate (how "done" was proven) |
|---|---|---|---|---|
| 0 | Foundations | A | Maven project (Java 21, Spring Boot 3.3), starter pack imported (`data/`, `docs/`, `scripts/`), `.gitignore`, PostgreSQL via Flyway `V1`, typed config | app boots, migrates, loads the catalog |
| 1 | Core platform (milestone 1) | A | node catalog loader, definition model, publish validator, graph analyzer (BFS/DFS), workflow CRUD, draft → publish, versions, per-run snapshot, seed loader | `DefinitionValidatorTest`, `GraphAnalyzerTest`; four seeds published at startup |
| 2 | Engine v1 (milestone 2) | A | queue table + worker loop, manual trigger, `condition`/`notify`/`http_request`/`delay` nodes, template resolver, per-step persistence, trace API | expense auto-approve branch end to end; `TemplateResolverTest` |
| 3 | Durability and gates (milestone 3) | A | leases + heartbeat, crash resume from persisted steps, stable idempotency keys, retries with backoff, step cap, approval node + approve/reject APIs, engine gate for `order_action`, webhook trigger, cancel | `scripts/smoke_test.py` 28/28; kill-and-resume + `duplication_check.py` PASS; `DurabilityIT` |
| 4 | AI nodes (milestone 4) | A | provider adapter (OpenAI-compatible + offline), schema enforcement with one repair retry, token accounting, injection defences | `AiNodeRunnerTest`, `AiGuardrailIT`; the five sample payloads against a real model |
| 5 | Console and verification (milestone 5) | A | static console (workflows, runs, trace, approvals), unit + integration suites, `run_drills.py`, verification report | all suites green; drills green on the container stack |
| 6 | Hardening and operability | B | Spring Security with roles, hashed secrets and redaction, SSRF allow-list, Micrometer metrics, MDC logging, graceful shutdown, poison-run guard, orphan sweep, API/worker/prod profiles | `ApiContractIT` (roles, hygiene), `EngineBehaviorIT` |
| 7 | Packaging and delivery | B | multi-stage Dockerfile (non-root, health check), Docker Compose (API, worker, second worker, postgres, mock world), GitHub Actions CI (build, tests, container smoke), deployment guide | `docker compose up` stack passes the smoke test and drills |
| 8 | Good To Have | B | natural-language compiler + eval, cron schedule trigger, wall-clock and token caps, immutable version history, replay of failed runs | `NlCompilerIT`, `WorkflowLifecycleIT`, `EngineBehaviorIT`, NL eval report |
| 9 | Documentation and demo | A+B | README, ARCHITECTURE, HLD, LLD, PROJECT_STRUCTURE, API, DEPLOYMENT, verification report, demo script | links resolve; every command in the README was run |

## Scope decisions

| Item | Decision | Reason |
|---|---|---|
| Worker placement | in-process by default, separate process supported | contract allows either; separate is the cleaner architecture and is what Compose runs |
| Loops | legal; `max_steps` bounds them | the contract requires polling patterns (`wf_runaway`) |
| Multi-worker | implemented (leases, `SKIP LOCKED`, heartbeat, same-id release), demonstrated by Compose `scale` profile and `DurabilityIT` | cheap once the queue is a leased table |
| Strict DAG, parallel fan-out, sub-workflows, `agent` node, cost accounting, multi-tenancy, real connectors | not implemented (Stretch) | outside the Must Have core; the executor and catalog are the extension points (ARCHITECTURE §10) |
| SSE/WebSocket live run view | not implemented; the console polls every 3 s | polling is sufficient and robust behind any proxy |
| AI failure triage | not implemented | the failed-step trace already carries error code, message and AI attempt log; triage would be an additive node |

## Requirement traceability

| Requirement (problem statement) | Implementation | Verified by |
|---|---|---|
| Load provided catalog, seeds, sample payloads; preserve ids and shape | `SeedLoader`, `NodeCatalog`; only the mock-world base URL is substituted (documented as engine configuration) | `ApiContractIT.seedWorkflowsAreLoadedAsPublished`, smoke §1 |
| Create, read, update, list workflows; Draft/Published | `WorkflowService`, `WorkflowController` | `WorkflowLifecycleIT`, smoke §2 |
| Per-run definition snapshot | `runs.definition_snapshot` | `EngineBehaviorIT.editingAndRepublishingNeverChangesWhatAnInFlightRunExecutes` |
| Publish validation: unknown type, missing param, bad reference, each with a clear error | `DefinitionValidator` | `DefinitionValidatorTest`, `ApiContractIT`, smoke §3 |
| Loops legal | validator warns only | `DefinitionValidatorTest.loopsAreLegalAndOnlyWarned` |
| Webhook trigger with `X-Relay-Secret`; manual trigger | `HookController`, `RunService` | `ApiContractIT`, smoke §4 |
| Deterministic nodes with templates; persisted resolved input and output | executors, `TemplateResolver` | `TemplateResolverTest`, trace assertions |
| AI node: schema, one repair retry, clean failure, token usage per step | `AiNodeRunner` | `AiNodeRunnerTest`, `AiGuardrailIT` |
| Injection payloads cannot cause unauthorized actions | engine gate, fixed targets, schema | `AiGuardrailIT`, drill 4 (real model) |
| Async execution; triggers only enqueue | `RunService` + `WorkerLoop` | `ApiContractIT.triggerReturnsPromptly...` |
| Every step persisted; resume from last completed step | `RunExecutor` cursor | `DurabilityIT`, drill 2 |
| Exactly-once side effects, idempotency keys, ledger proof | stable key on the step row | drills 2 and 3 + `duplication_check.py`, `DurabilityIT.theCrashWindow...` |
| Approval node, approve/reject, decider recorded | `ApprovalService` | `ApiContractIT`, smoke §5 |
| Sensitive nodes unreachable without approval, regardless of AI | `RunExecutor` gate | `EngineBehaviorIT` gate tests |
| Per-node retries with exponential backoff; exhausted retries → failed with error | `RetryPolicy`, `RunExecutor` | `EngineBehaviorIT`, drill 5 |
| Step cap with reason naming the cap | `RunExecutor.checkCaps` | smoke §6, `EngineBehaviorIT`, drill 6 |
| Status vocabulary | `RunStatus` + DB CHECK | smoke §7 |
| Trace API; console with list, history, trace, pending approvals | `RunController`, `static/` | `ApiContractIT`, manual walkthrough |
| Timeouts on every external call; AI behind an adapter | `ExternalHttpClient`, `OpenAiCompatibleProvider`, `AiProvider` | code review, `AiNodeRunnerTest` |
| Automated tests for validation, templates, schema, idempotency, guardrails | see LLD §6 | 130+ tests |
| README, architecture, state machine, exactly-once explanation, demo steps | `README.md`, `ARCHITECTURE.md`, `HLD.md`, `LLD.md` | review |
| Verification report: smoke output + duplication check after live kill-and-resume | `scripts/run_drills.py` → `docs/VERIFICATION_REPORT.md` | generated from a real run |
| Explainer video | `docs/DEMO_SCRIPT.md` | recorded by the author |
| Good To Have: NL compiler + eval | `compiler` package | `NlCompilerIT`, `docs/NL_EVAL_REPORT.md` |
| Good To Have: schedule, extra caps, versions, replay | `ScheduleDispatcher`, `RunExecutor`, `WorkflowService`, `RunService` | `WorkflowLifecycleIT`, `EngineBehaviorIT` |
| Stretch: multiple concurrent workers | leased queue | `DurabilityIT`, Compose `scale` profile |

## Test strategy

1. **Unit** (`*Test`, fast, no Docker): validation, graph analysis, templates, conditions, retry maths, schema enforcement, hashing, egress policy.
2. **Integration** (`*IT`, Testcontainers PostgreSQL, in-JVM fake world with a ledger and failure injection, the full Spring context): API contract, engine semantics, durability, AI guardrails, lifecycle, compiler.
   The durability tests disable the background worker and drive the executor directly, so "kill" (revoke the lease) and "restart" (execute again) are deterministic.
3. **System** (`scripts/smoke_test.py`, `scripts/run_drills.py`): the provided smoke test plus real kill -9 of the worker container, the crash-window drill, injection with a real model, outage, flakiness, step cap, schema enforcement.
4. **CI** (`.github/workflows/ci.yml`): `mvn verify`, then the container stack with the smoke test and drills.

## Definition of done (per phase)

Code compiles and the suites listed in the gate pass · behaviour is documented where users will look for it · no secret is committed or logged · the change is reflected in the traceability table.
