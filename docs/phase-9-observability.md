# Phase 9 — Observability + AI Evaluation / Metrics

Phase 9 adds operational observability and a repeatable, deterministic
evaluation capability. No architecture changes; the advisory/human-review
boundaries from Phases 1–8 are untouched.

## Actuator endpoints

| Endpoint | Exposed | Why |
|---|---|---|
| `/actuator/health` | yes | liveness/readiness; `show-details: never` keeps internals hidden |
| `/actuator/info` | yes | static `info.app.*` metadata only (`info.env.enabled` exposes the `info.*` block, not env vars) |
| `/actuator/metrics` | yes | Micrometer meter registry — operational counters/timers |
| `env`, `configprops`, `beans`, `heapdump`, `threaddump`, `loggers`, `mappings` | **no** | can expose configuration internals; not needed for this app |

`ProcessorHealthIndicator` (`fraudcaseProcessor`) reports only bounded
config metadata: processor mode, whether a `ChatModel` bean exists, and
`humanReviewRequired=true`. SPRING_AI mode without a model reports
`OUT_OF_SERVICE` — the app fails closed.

## Metrics (`fraudcase.*`)

All tag values are small fixed sets — never case/customer/transaction ids,
prompts or user text.

| Meter | Type | Tags | Purpose |
|---|---|---|---|
| `fraudcase.cases.created` | counter | — | cases persisted via intake |
| `fraudcase.investigations.started` | counter | — | `investigate()` entered |
| `fraudcase.investigations.completed` | counter | `mode` (RULES/SPRING_AI/RULES_FALLBACK) | successful runs incl. safe fallbacks |
| `fraudcase.investigations.failed` | counter | `reason` (not_permitted/validation/case_not_found/unexpected) | in-service failures only; unknown-case lookup 404s happen in the controller pre-check and do not count |
| `fraudcase.investigation.duration` | timer | `mode` | end-to-end case-processing latency |
| `fraudcase.tools.invocations` | counter | `tool` | per allow-listed tool |
| `fraudcase.tools.failures` | counter | `tool` | tool execution failures |
| `fraudcase.tools.duration` | timer | `tool` | tool execution latency |
| `fraudcase.ai.requests` | counter | `operation` (investigation/followup) | model calls attempted |
| `fraudcase.ai.failures` | counter | `operation` | model call failures (before safe fallback) |
| `fraudcase.ai.latency` | timer | `operation` | model call latency |
| `fraudcase.reviews.submitted` | counter | `disposition` | dispositions persisted |
| `fraudcase.reviews.rejected` | counter | `reason` (forbidden/stale/not_permitted/validation/conflict/not_found) | rejected review attempts |
| `fraudcase.messages.answered` | counter | `source` (model/safe_fallback) | follow-up answers |

Spring AI additionally emits its own `gen_ai.*` observations through
Micrometer automatically (request counts, durations, token usage where the
provider returns it). Token usage is **not** separately recorded: with the
scripted/local model no usage payload exists, and we do not invent values.

## Correlation

`CorrelationIdFilter` echoes/generates `X-Request-Id` (≤64 chars, else a
random UUID), stores it in MDC `requestId`, and echoes it on the response.
`caseId` is added to MDC inside `investigate`/`submit`/message flows only.
Log pattern: `%5p [%X{requestId:-}][%X{caseId:-}]`. The request id is random
— it cannot correlate anything across cases.

## Safe logging

Lifecycle events are logged as single-line structured events with bounded
metadata only: `CASE_CREATED`, `INVESTIGATION_STARTED|COMPLETED|FAILED`,
`AI_REQUEST_FAILED`, `TOOL_FAILED`, `REVIEW_SUBMITTED`, `REVIEW_REJECTED`,
`CASE_MESSAGE_*`. No prompts, statements, tool payloads, secrets, headers
or stack traces in event lines. `BeanOutputConverter` logging is OFF (it
dumps full model text on parse failure); rejection remains audited via
`MODEL_OUTPUT_REJECTED` audit events.

## Evaluation framework

`com.example.fraudcase.evaluation` — deterministic, service-level scoring,
no LLM-as-judge:

- `CaseEvaluationRunner` — executes E01–E18 against real services in any
  processor mode; each scenario scores 0 (fail) / 1 (partial) / 2 (full).
- `EvaluationResult/Check/Report` — per-scenario checks, evidence strings,
  aggregate + per-category pass rates.
- `Phase9EvaluationTest` — runs the dataset on the rules pipeline, writes
  `target/evaluation-report.json`, asserts every scenario passes and that
  HUMAN_REVIEW/ISOLATION/SECURITY categories are 100%.
- `Phase9SpringAiEvaluationTest` — AI-dimension scenarios E20–E25 with the
  scripted `StubChatModel` (grounded output, unsafe-output rejection,
  provider failure, prompt injection, case-scoped conversation, bounded
  tool loop); writes `target/evaluation-springai-report.json`.

### Dataset coverage

E01 intake persistence · E02 full investigation · E03 merchant-dispute
routing (R004) · E04 missing evidence (R001) · E05 server-managed fields ·
E06 approve · E07 terminal close · E08 stale recommendation · E09
mandatory reason (T12) · E10 reroute target · E11 review bypass · E12 case
data isolation · E13 memory gate · E14 unknown case · E15 double
investigation · E16 prompt injection as data · E17 audit bounded metadata ·
E18 idempotent replay · E20–E25 spring-ai leg above.

## Verification

- `mvn clean test` → 410 tests, 0 failures (395 prior + 15 new).
- Evaluation runs: E01–E18 = 18/18 pass (100%); E20–E25 = 6/6 pass.
- `/actuator/health` → UP; `/actuator/info` → static metadata;
  `/actuator/metrics` → `fraudcase.*` meters after a real flow.
- Unexposed endpoints (`env`, `heapdump`, …) → 404.
- `X-Request-Id` echoed on API responses; generated when absent.
