# Project structure

```
relay-ai-workflow-orchestrator/
├── README.md                      entry point: quick start, demo walkthrough, how it works, API overview, coverage
├── PROJECT_STRUCTURE.md           this file
├── RELAY_PROBLEM_STATEMENT.md     the capstone problem statement (as provided)
├── pom.xml                        Maven build: Spring Boot 3.3, Java 21, Flyway, Testcontainers, JaCoCo
├── Dockerfile                     multi-stage build; non-root JRE image with a health check
├── docker-compose.yml             postgres + mock world + API + worker (+ second worker via --profile scale)
├── .env.example                   documented configuration template (the real .env is git-ignored)
├── .gitignore  .dockerignore      secrets, build output, IDE files, local state are never committed or shipped
│
├── .github/workflows/ci.yml       CI: mvn verify, container smoke test + drills, image build
│
├── data/                          the provided capstone data (single source of truth; bundled into the jar)
│   ├── node_catalog.json          node + trigger registry: the boundary of every definition
│   ├── seed_workflows.json        wf_support_triage, wf_expense_approval, wf_slow_fulfillment, wf_runaway
│   ├── sample_payloads.jsonl      pay_001 … pay_201 incl. the two prompt-injection payloads
│   └── nl_eval.jsonl              15 labelled cases for the NL compiler (12 buildable, 3 traps)
│
├── docs/
│   ├── ARCHITECTURE.md            principles, state machines, durable execution, exactly-once analysis, decision log
│   ├── HLD.md                     high-level design: context, flows (sequence diagrams), deployment, NFRs, risks
│   ├── LLD.md                     packages and classes, algorithms, transactions, DDL, error matrix, test map
│   ├── IMPLEMENTATION_PLAN.md     phases, scope decisions, requirement traceability, test strategy
│   ├── API.md                     full REST reference, roles, error codes, metrics
│   ├── DEPLOYMENT.md              deployment modes, configuration table, AWS reference topology, operations
│   ├── DEMO_SCRIPT.md             scene-by-scene script for the explainer video
│   ├── VERIFICATION_REPORT.md     generated: smoke output, kill-and-resume and crash-window ledgers, injection, outage, caps
│   ├── NL_EVAL_REPORT.md          generated: per-case compiler results, accuracy, analysis of misses
│   ├── API_CONTRACT.md  DATA_MODEL.md  EVALUATION_GUIDE.md  IMPLEMENTATION_GUIDE.md   provided starter docs
│   └── STARTER_PACK_README.md     the starter pack's own README
│
├── scripts/
│   ├── smoke_test.py              provided: contract and lifecycle check
│   ├── duplication_check.py       provided: exactly-once proof from the world's ledger
│   ├── mock_world.py              provided: external world with ledger, idempotency and failure injection
│   ├── mock_provider.py  validate_pack.py   provided helpers
│   ├── run_drills.py              runs the smoke test and every drill (real kill -9, injection, outage, caps) → report
│   └── run_nl_eval.py             runs the NL eval through a live API → docs/NL_EVAL_REPORT.md
│
└── src/
    ├── main/
    │   ├── java/com/relay/
    │   │   ├── RelayApplication.java            Spring Boot entry point
    │   │   ├── config/
    │   │   │   └── RelayProperties.java         typed relay.* configuration (api, worker, engine, ai, http, scheduler, seed)
    │   │   ├── domain/                          RunStatus, StepStatus, ApprovalStatus; records for workflow, version, run,
    │   │   │                                    step, approval; RelayException (HTTP status + stable code)
    │   │   ├── definition/
    │   │   │   ├── NodeCatalog.java             loads the catalog into node / trigger / param specs
    │   │   │   ├── WorkflowDefinition.java      typed read-only view over a definition JSON
    │   │   │   ├── DefinitionValidator.java     publish-time validation, one code + message per case
    │   │   │   └── GraphAnalyzer.java           BFS reachability, DFS loop detection, approval-path analysis
    │   │   ├── repository/                      SQL (JdbcClient): Workflow, Run, Step, Approval, Queue, Schedule; JsonCodec
    │   │   ├── service/
    │   │   │   ├── WorkflowService.java         create / update / publish / versions / validate; secret hashing; schedules
    │   │   │   ├── RunService.java              trigger (manual, webhook, schedule), cancel, replay
    │   │   │   ├── ApprovalService.java         approve / reject (the only writer of approved rows)
    │   │   │   ├── SeedLoader.java              loads the four seed workflows as Published, race-safe
    │   │   │   └── InvalidDefinitionException.java
    │   │   ├── engine/
    │   │   │   ├── RunExecutor.java             the durable execution loop: cursor, caps, gate, retries, parking
    │   │   │   ├── TemplateResolver.java        {{path}} resolution (single pass, typed); TemplateContext, TemplateException
    │   │   │   ├── RetryPolicy.java             exponential backoff with jitter
    │   │   │   ├── ExternalHttpClient.java      the only outbound HTTP (timeouts, no redirects); EgressPolicy: SSRF allow-list
    │   │   │   ├── NodeExecutionException.java  retryable vs permanent failures; WorkerLease
    │   │   │   └── node/                        one class per node type:
    │   │   │       ├── NodeExecutor.java        the SPI
    │   │   │       ├── HttpRequestExecutor.java  ConditionExecutor.java  NotifyExecutor.java
    │   │   │       ├── OrderActionExecutor.java  AiExecutor.java
    │   │   │       └── WorldClient.java         idempotency-keyed calls to the world
    │   │   ├── ai/
    │   │   │   ├── AiProvider.java              model adapter SPI
    │   │   │   ├── OpenAiCompatibleProvider.java  OpenRouter / Groq / Gemini / Ollama / OpenAI
    │   │   │   ├── HeuristicAiProvider.java     deterministic offline model for tests, CI and keyless demos
    │   │   │   ├── AiProviderConfig.java        provider selection, fails fast on misconfiguration
    │   │   │   ├── AiNodeRunner.java            validate → one repair retry → fail cleanly; token accounting
    │   │   │   └── JsonSchemaService.java       JSON Schema validation incl. meta-schema check at publish
    │   │   ├── worker/
    │   │   │   ├── WorkerLoop.java              claim, execute, heartbeat, orphan sweep, graceful stop
    │   │   │   └── ScheduleDispatcher.java      fires due cron schedules exactly once
    │   │   ├── compiler/
    │   │   │   ├── NlCompilerService.java       English → Draft or refusal, validate-and-repair loop
    │   │   │   └── NlEvalService.java           scores data/nl_eval.jsonl per the evaluation guide
    │   │   ├── security/                        SecurityConfig, BearerTokenFilter, PrincipalRegistry, RelayPrincipal, SecretHasher
    │   │   ├── web/                             controllers (workflow, hook, run, approval, compiler, catalog), ApiExceptionHandler
    │   │   │   └── dto/Views.java               response records
    │   │   └── observability/RelayMetrics.java  Micrometer counters, timers, gauges
    │   └── resources/
    │       ├── application.yml                  defaults; application-{api,worker,prod}.yml = deployment profiles
    │       ├── db/migration/V1__init_schema.sql Flyway: workflows, versions, runs, steps, approvals, queue_jobs, schedules
    │       └── static/                          the operations console (index.html, console/app.js, console/styles.css)
    └── test/java/com/relay/
        ├── TestSupport.java                     shared helpers (catalog, validator, seeds)
        ├── support/                             AbstractIntegrationTest (Testcontainers PostgreSQL), FakeWorld (in-JVM ledger)
        ├── definition/ ai/ engine/ security/    unit tests: validator, graph analyzer, templates, conditions, retries,
        │                                        schema enforcement, injection payloads, egress policy, hashing
        └── *IT.java                             integration tests: ApiContract, EngineBehavior, Durability, AiGuardrail,
                                                 WorkflowLifecycle, NlCompiler
```

## Dependency rules

```
web ──► service ──► repository ──► PostgreSQL
 │         │
 │         └──► definition (validation, graph analysis)
 └──► compiler ──► ai, definition, service
worker ──► engine ──► engine.node ──► (WorldClient | ExternalHttpClient | ai)
                  └──► repository, definition
security, observability, config: cross-cutting, depended on by the layers above, never the reverse
```

Nothing above `repository` contains SQL; nothing outside `engine.node` and `ai` performs outbound calls; the engine depends on the `NodeExecutor` and `AiProvider` interfaces, so both are replaceable in tests.
