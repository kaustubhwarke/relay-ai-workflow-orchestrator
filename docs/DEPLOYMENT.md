# Deployment and operations guide

## Deployment modes (one artifact)

| Mode | How | Use |
|---|---|---|
| API + worker in one process (default) | `java -jar relay.jar` | local development, demos, small installs |
| API only | `--spring.profiles.active=api` (or `RELAY_WORKER_ENABLED=false`) | stateless tier behind a load balancer |
| Worker only | `--spring.profiles.active=worker` | scale execution independently; exposes only health and metrics on `PORT` (default 8081) |
| Hardened | add `prod`: `--spring.profiles.active=prod,api` | refuses to start with the default demo token; disables Swagger, detailed health, and seeding |

All state is in PostgreSQL, so any mix of API and worker processes may run against one database. Workers coordinate through leased queue
rows (`FOR UPDATE SKIP LOCKED`); running several is safe and is the default behaviour, not a special mode.

## Local: Docker Compose

```bash
cp .env.example .env            # then edit: RELAY_API_TOKEN, and OPENAI_API_KEY for the model-backed demos
docker compose up -d --build    # postgres, mock world, API (8080), worker
docker compose --profile scale up -d    # + a second worker
open http://localhost:8080      # the console; Connect with RELAY_API_TOKEN
```

`RELAY_AI_PROVIDER=heuristic` runs the whole platform with no API key and no network (deterministic offline model; the NL compiler is the
one feature that needs a real model). `.env` is git-ignored and excluded from the image by `.dockerignore`.

Kill-and-resume on the container stack:

```bash
docker compose kill -s KILL relay-worker    # SIGKILL, no graceful shutdown
docker compose start relay-worker           # the run resumes from its last persisted step
```

## Local without containers

```bash
docker run -d --name relay-postgres -e POSTGRES_DB=relay -e POSTGRES_USER=relay -e POSTGRES_PASSWORD=relay -p 5432:5432 postgres:16-alpine
python scripts/mock_world.py --port 9210 &
mvn -DskipTests package
set -a; . ./.env; set +a
java -jar target/relay.jar
```

Build with a plain Maven settings file if your `~/.m2/settings.xml` points at private repositories: `mvn -s <empty settings.xml> ...`.

## Configuration reference

Every property can be set as an environment variable (Spring relaxed binding), e.g. `relay.worker.lease-seconds` → `RELAY_WORKER_LEASE_SECONDS`.

| Property (env) | Default | Meaning |
|---|---|---|
| `spring.datasource.url` (`RELAY_DB_URL`, `RELAY_DB_USER`, `RELAY_DB_PASSWORD`) | `jdbc:postgresql://localhost:5432/relay` | PostgreSQL; Flyway migrates on start |
| `relay.security.api-token` (`RELAY_API_TOKEN`) | `relay-demo-token` | demo token with all roles; **must be changed in `prod`** |
| `relay.security.tokens[n].{name,token,roles}` | none | additional principals for role separation |
| `relay.mock-world.url` / `timeout-ms` (`RELAY_MOCK_WORLD_URL`) | `http://localhost:9210` / 5000 | target of `notify` and `order_action`; also the seed workflows' base URL |
| `relay.http.allowed-hosts` (`RELAY_HTTP_ALLOWED_HOSTS`) | empty | extra hosts `http_request` may call (SSRF allow-list); the mock-world host is always allowed |
| `relay.worker.enabled` (`RELAY_WORKER_ENABLED`) | `true` | run the worker loop in this process |
| `relay.worker.id` (`RELAY_WORKER_ID`) | `worker-<hostname>` | **unique per live process**; a restarted process releases leases left by its previous incarnation |
| `relay.worker.concurrency` | 1 | worker threads per process |
| `relay.worker.lease-seconds` / `heartbeat-seconds` | 15 / 5 | queue lease and its renewal |
| `relay.worker.max-claims` | 10 | a run claimed more often without finishing is quarantined as failed |
| `relay.engine.default-max-attempts` / `backoff-base-ms` / `backoff-max-ms` | 4 / 500 / 10000 | retry policy (node-level `retry` overrides) |
| `relay.engine.trace-store-ai-prompts` | `true` | store resolved prompts in traces; `false` stores only a hash and length |
| `relay.ai.provider` (`RELAY_AI_PROVIDER`) | `heuristic` | `openai` (any OpenAI-compatible endpoint) or `heuristic` |
| `relay.ai.base-url` / `api-key` / `model` (`OPENAI_BASE_URL`, `OPENAI_API_KEY`, `RELAY_AI_MODEL`) | OpenRouter / empty / `openai/gpt-4o-mini` | model endpoint (OpenRouter, Groq, Gemini, Ollama, OpenAI) |
| `relay.ai.timeout-ms` | 30000 | per model call |
| `relay.scheduler.enabled` / `poll-interval-ms` | `true` / 5000 | cron schedule dispatcher (runs in the worker) |
| `relay.seed.enabled` (`RELAY_SEED_ENABLED`) | `true` | load the four seed workflows at start (idempotent, race-safe) |
| `relay.api.enabled` | `true` | `false` for worker-only processes |

## Running on AWS (reference topology)

```
 Route 53 → ALB (TLS) → ECS Fargate service "relay-api"   (2+ tasks, profile prod,api)   ┐
                         ECS Fargate service "relay-worker" (2+ tasks, profile prod,worker)├→ RDS PostgreSQL 16 (Multi-AZ)
 Secrets Manager → task env: RELAY_API_TOKEN, OPENAI_API_KEY, RELAY_DB_PASSWORD         ┘
 CloudWatch Logs + Prometheus scrape of /actuator/prometheus (workers on :8081)
```

* Build and push the image from the `Dockerfile` (multi-stage, non-root, JRE 21, container health check).
* Scale workers on `relay_queue_ready` (jobs claimable now); scale the API on CPU/latency.
* Give every worker task a unique `RELAY_WORKER_ID` (for example from the ECS task id) so a replaced task can release its own leases.
* Use the ALB health check on `/actuator/health/readiness`; the worker health port is `8081`.
* Egress: the engine only calls hosts on the allow-list; restrict the task security group to the model provider, the world endpoint and RDS.

## Operating notes

* **Backups and migrations**: schema is versioned with Flyway (`src/main/resources/db/migration`); take RDS snapshots before upgrades.
* **Retention**: `steps` grows with every run. Archive or delete finished runs older than your retention window (cascade removes steps and approvals).
* **Poison runs**: `max_claims_exceeded` marks a run that keeps crashing its worker; investigate the worker log lines tagged `[run=<id>]`.
* **Logs** carry `[run=<id>] [step=<node>]` via MDC. Metrics are described in [API.md](API.md#reference-and-operations).
* **Shutdown**: on SIGTERM the worker stops claiming, interrupts in-flight steps and releases its jobs so another worker resumes them at once; SIGKILL is also safe, just slower (the lease expires, or the restarted process reclaims its own leases).
