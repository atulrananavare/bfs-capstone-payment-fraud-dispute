# Intelligent Payment Fraud Investigation & Dispute Resolution Agent

An explainable, advisory assistant for payment fraud investigation and
dispute-resolution teams — built with **Spring Boot** and **Spring AI**
(adapter wired in Phase 3).

The application helps analysts capture fraud/dispute cases, collect evidence
through approved tools, retrieve applicable synthetic policy, and produce a
structured, evidence-linked recommendation. **It is an investigation and
recommendation assistant — not an autonomous banking decision system.**

## Safety Scope

- **Synthetic training data only.** No real customer, card or account data
  may ever enter this codebase.
- **Every recommendation requires human review** (`humanReviewRequired` is
  always `true`); final disposition belongs to an authorised reviewer.
- **No autonomous actions.** The system can never block cards, issue
  refunds/credits, move funds, modify accounts, file reports or contact
  customers. These capabilities are permanently out of scope.
- Safety flags are enforced at startup: setting
  `capstone.human-review-required=false`,
  `capstone.allow-financial-actions=true` or
  `capstone.allow-customer-contact=true` fails the context.

## Environment

| Tool  | Version detected on this VM          |
|-------|--------------------------------------|
| Java  | OpenJDK 21 (21.0.12)                 |
| Maven | 3.8.7                                |

No external services are required in the default rules mode: persistence is
an embedded H2 database initialised by Flyway migrations, and no AI provider,
vector store or credential is needed. Spring AI mode additionally needs an
Anthropic API key supplied via environment variable.

## Technology Choices

| Component     | Selection                                     | Reason |
|---------------|-----------------------------------------------|--------|
| Spring Boot   | 3.5.16 (parent POM)                            | Stable, widely supported release line; fully compatible with Java 21 and already resolvable in this environment. |
| Spring AI     | 1.1.8 (BOM; `spring-ai-client-chat` + `spring-ai-anthropic`) | Phase 3 adapter. Raw artifacts only — no autoconfigure starter, so no provider client exists unless the app wires one. The domain layer never sees Spring AI types. |
| Web           | spring-boot-starter-web                        | REST API surface. |
| Validation    | spring-boot-starter-validation                 | Declarative intake validation (Jakarta Bean Validation). |
| UI            | spring-boot-starter-thymeleaf                  | Selected server-rendered UI path for later phases. |
| Observability | spring-boot-starter-actuator                   | Safe health endpoint foundation; Micrometer-based metrics come later. |
| Persistence   | spring-boot-starter-data-jpa + H2 (embedded)   | Durable, test-friendly storage with no external service. H2 runs in `MODE=LEGACY` in-memory for this capstone. |
| Migrations    | Flyway (`flyway-core`)                              | Versioned SQL owns the schema; Hibernate runs `ddl-auto=validate` only. |
| Testing       | spring-boot-starter-test (JUnit 5, AssertJ, Mockito) | Deterministic unit + web-slice + persistence-slice tests; no live AI needed. |

Java level: **21** (matches the installed JDK; no upgrade performed).

## Build / Test / Run

```bash
# clean build + full test suite (410+ tests incl. T01–T12, security,
# evaluation and observability)
mvn clean verify

# tests only
mvn test

# deterministic evaluation dataset only (E01–E18)
mvn test -Dtest=Phase9EvaluationTest

# spring-ai evaluation leg only (scripted model, E20–E25)
mvn test -Dtest=Phase9SpringAiEvaluationTest

# run locally in rules mode (no credentials needed)
mvn spring-boot:run

# run in spring-ai mode (needs ANTHROPIC_API_KEY in the environment)
ANTHROPIC_API_KEY=sk-... mvn spring-boot:run \
  -Dspring-boot.run.arguments=--capstone.processor-mode=spring-ai

# if port 8080 is already in use on your machine:
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085
```

Then:

- `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`
- `POST http://localhost:8080/api/cases` → validate + capture a case
- `GET http://localhost:8080/api/cases` → analyst worklist (masked
  summaries, newest first)
- `GET http://localhost:8080/api/cases/{caseId}` → case summary + state
- `POST http://localhost:8080/api/cases/{caseId}/investigations` → run the
  deterministic investigation (allowed from `NEW` and `INCOMPLETE`)
- `GET http://localhost:8080/api/cases/{caseId}/evidence` → collected evidence
- `GET http://localhost:8080/api/cases/{caseId}/tool-traces` → tool execution
  traces (hashed requests, redacted summaries)
- `GET http://localhost:8080/api/cases/{caseId}/recommendation` → latest
  validated recommendation
- `POST http://localhost:8080/api/cases/{caseId}/messages` → case-scoped
  conversational follow-up (spring-ai mode only)
- `GET http://localhost:8080/api/cases/{caseId}/messages` → ordered
  persisted conversation history for the case
- `POST http://localhost:8080/api/cases/{caseId}/reviews` → mandatory
  human-review disposition (`ESCALATE`/`REROUTE`/`OVERRIDE` → `ROUTED`,
  `REQUEST_MORE_INFORMATION` → `INCOMPLETE`, `CLOSE` → `CLOSED`)
- `GET http://localhost:8080/api/cases/{caseId}/reviews` → append-only
  review history
- `GET http://localhost:8080/actuator/health|info|metrics` →
  observability surface (all other actuator endpoints stay unexposed)

### Example intake request

```bash
curl -X POST http://localhost:8080/api/cases \
  -H 'Content-Type: application/json' \
  -d '{
    "reportedAt": "2026-11-11T09:15:00Z",
    "channel": "WEB_PORTAL",
    "customer": {"maskedCustomerRef": "CUST-••••-4821", "homeRegion": "IN-KA"},
    "transaction": {
      "transactionRef": "TXN-2026-784102",
      "amount": 48750.00,
      "currency": "INR",
      "occurredAt": "2026-11-06T08:42:00+05:30",
      "status": "POSTED",
      "channel": "CARD_NOT_PRESENT",
      "maskedMerchant": "NORTHSTAR DIGITAL ••••73"
    },
    "claim": {
      "issueType": "MERCHANT_NOT_RECOGNISED",
      "customerStatement": "I do not recognise this purchase and still have my card.",
      "cardPossession": "IN_POSSESSION",
      "merchantRecognised": "NOT_RECOGNISED"
    },
    "consent": {"syntheticDataConfirmed": true, "reviewRequired": true}
  }'
```

## Configuration

All settings live under `capstone.*` (see `application.yml` and
`application-example.yml`):

| Key | Default | Meaning |
|-----|---------|---------|
| `processor-mode` | `rules` | Processor: `rules` (deterministic, no AI) or `spring-ai` (Spring AI adapter + rules fallback). |
| `human-review-required` | `true` | Startup-enforced invariant. |
| `allow-financial-actions` | `false` | Startup-enforced invariant. |
| `allow-customer-contact` | `false` | Startup-enforced invariant. |
| `retrieval.top-k` | `4` | Future policy RAG top-K. |
| `retrieval.similarity-threshold` | `0.72` | Future policy RAG threshold. |
| `memory.max-messages` | `12` | Bounded active-memory window per case (Phase 5); the full record stays persisted. |
| `tools.timeout-ms` | `2500` | Per-tool timeout bound for evidence tool execution. |
| `tools.deterministic-seed` | `fraud-training-v1` | Deterministic evidence seed — same input → same evidence. |
| `ai.timeout-ms` | `10000` | Per model-invocation connect/read bound (spring-ai mode only). |
| `ai.temperature` | `0.0` | Sampling temperature for the structured answer. |
| `ai.max-tool-calls` | `6` | Per-investigation model tool-call budget. |
| `ai.max-rounds` | `4` | Tool-call loop bound per investigation. |
| `ai.max-output-tokens` | `1024` | Bound on the structured answer. |

Spring AI mode is enabled via `capstone.processor-mode=spring-ai` plus
`spring.ai.anthropic.api-key` (environment variable only — never committed)
and `spring.ai.anthropic.chat.options.model`. Without a key, spring-ai mode
refuses to start; at runtime any provider failure or invalid output falls
back to the deterministic rules processor.

Datasource defaults: embedded H2
(`jdbc:h2:mem:fraudcasedb;DB_CLOSE_DELAY=-1;MODE=LEGACY`, user `sa`, empty
password — local synthetic database only, no real credential exists).

## Architecture Overview

```
fraudcase/
├── api/              REST controllers (intake + investigation), DTOs,
│   │                 safe error model
│   └── validation/   custom validators (masking, ISO currency, markup)
├── application/      FraudCaseService (intake), InvestigationService
│                     (orchestration), ProcessorSelector,
│                     ToolExecutionService, AuditService, EvidenceRecorder
├── domain/           Framework-neutral model: case state machine,
│                     classification/route/risk enums, intake enums,
│                     FraudCase, FraudCaseContext, FraudCaseRecommendation,
│                     EvidenceItem, PolicyCitation, audit enums —
│                     NO Spring AI imports, ever
├── agent/
│   ├── common/       FraudCaseProcessor contract + RecommendationValidator
│   ├── rules/        RulesFraudCaseAgent — deterministic R001–R008 engine
│   └── springai/     SpringAiFraudCaseAgent, prompt builder, case-bound
│                     tool adapter, output DTO + typed exceptions —
│                     the ONLY package that imports Spring AI chat types
├── tools/            Six deterministic synthetic evidence tools +
│                     Determinism helper (seeded hashing, fixture pinning)
├── persistence/      JPA entities, Spring Data repositories,
│                     restricted AuditEventAppender, FraudCaseMapper,
│                     JpaFraudCaseStore
└── config/           CapstoneProperties (validated safety config), beans
```

Full layer descriptions and boundaries: [`docs/architecture.md`](docs/architecture.md).

## Phase 2 Capabilities (current)

Everything from Phase 1, plus:

- **Durable persistence.** Flyway-managed schema (12 tables), JPA entities,
  Spring Data repositories; Hibernate validates the schema rather than
  generating it. Cases, claims, evidence, tool traces, recommendations,
  policy citations, review dispositions and audit events all persist.
- **Six deterministic evidence tools** (`getTransactionDetails`,
  `getDeviceSignals`, `compareRecentLocations`, `getMerchantRiskProfile`,
  `getPriorDisputes`, `calculateCaseSla`) — typed request/result, pinned
  synthetic fixtures + seeded hash derivation, `SUCCESS`/`NOT_FOUND`/
  `INVALID_REQUEST` statuses, provenance, structured failures, no external
  calls.
- **`RulesFraudCaseAgent`** (`agent.rules`) — the deterministic
  `FraudCaseProcessor`. Precedence-ordered rules R001–R008 produce
  classification, route, risk, confidence, missing information,
  contradictions and rule IDs with triggering-fact rationale. No model,
  provider, embedding or vector dependency.
- **`RecommendationValidator`** — rejects recommendations that break
  invariants (human review, confidence range, case-local evidence/traces,
  known rule IDs, evidence presence unless INSUFFICIENT_EVIDENCE) before
  anything is persisted.
- **`InvestigationService`** orchestration — loads the persistent case,
  executes all tools, persists evidence + tool traces + append-only audit
  events, runs the rules agent, validates + persists the recommendation,
  drives state transitions (NEW/INCOMPLETE → ENRICHING →
  READY_FOR_REVIEW or back to INCOMPLETE when evidence is insufficient).
- **Append-only audit trail.** `AUDIT_EVENT` rows capture case lifecycle,
  tool executions and recommendation creation; the
  `AuditEventAppender` repository exposes no update/delete methods and the
  entity is guarded by `@PreUpdate`/`@PreRemove`.

### Deterministic rules (precedence order, first match wins)

| Rule | Trigger | Classification → Route (risk, base confidence) |
|------|---------|------------------------------------------------|
| R001 | Mandatory transaction evidence absent / not found | INSUFFICIENT_EVIDENCE → MANUAL_REVIEW (MEDIUM, 0.30; state INCOMPLETE) |
| R002 | Confirmed duplicate-processing fingerprint | DUPLICATE_PROCESSING → PAYMENT_OPERATIONS (MEDIUM, 0.95) |
| R004 | Customer recognises merchant + issue is amount/cancellation/non-receipt | MERCHANT_DISPUTE → DISPUTE_OPERATIONS (MEDIUM, 0.80) |
| R003 | ≥2 independently sourced high-severity anomalies | UNAUTHORISED_TRANSACTION (customer denies) or SUSPICIOUS_TRANSACTION → FRAUD_INVESTIGATION (HIGH, 0.85/0.75) |
| R006 | Denial contradicted by ≥2 independent benign signals | INSUFFICIENT_EVIDENCE → MANUAL_REVIEW (MEDIUM, 0.25; contradiction recorded) |
| R007 | Exactly 1 high-severity anomaly | SUSPICIOUS_TRANSACTION → FRAUD_INVESTIGATION (MEDIUM, 0.55) |
| R008 | No anomalies, no dispute pattern | GENUINE_TRANSACTION → MANUAL_REVIEW (LOW, 0.70) |

R005 is reserved for policy grounding in the RAG phase and is never faked.
Confidence is a deterministic rule score (documented per rule), reduced by
0.05 per unavailable non-mandatory tool to a floor of 0.05.

The rules never invent an authentication outcome: when the transaction tool
reports `authOutcome=UNAVAILABLE`, the recommendation records
`transaction authentication outcome unavailable` in `missingInformation`.

## Phase 3 Capabilities (current)

Everything from Phases 1–2, plus a real Spring AI adapter behind the same
`FraudCaseProcessor` contract:

- **`SpringAiFraudCaseAgent`** (`agent.springai`) — drives a `ChatModel`
  with a trusted system prompt (classpath resource, never returned by the
  API) and an untrusted-data user prompt. Structured output is bound to a
  typed DTO (`BeanOutputConverter`) and mapped to the framework-neutral
  `FraudCaseRecommendation`; the adapter never persists raw output.
- **Bounded, case-scoped model tools.** Exactly the six allow-listed
  deterministic tools are registered via `FunctionToolCallback`s. Every
  callback enforces case-scope (arguments must equal the case's own masked
  references), a shared `ai.max-tool-calls` budget and an elapsed-time
  bound; executions run through `ToolExecutionService` so they produce real
  traces and persisted evidence — never fabricated calls. The tool loop is
  owned by the agent and bounded by `ai.max-rounds`.
- **Validation before persistence.** Every AI-produced recommendation goes
  through `RecommendationValidator` in `SPRING_AI` mode: wrong case id,
  foreign evidence/trace ids, claimed rule ids, policy citations (no
  retrieval exists), out-of-range confidence, `humanReviewRequired=false`,
  oversized or action-claiming rationale are all rejected.
- **Deterministic fallback.** Provider failure, malformed output or
  validation rejection triggers the rules processor; the result persists
  with mode `RULES_FALLBACK` and the whole path is audited
  (`MODEL_INVOCATION_STARTED/FAILED`, `MODEL_OUTPUT_REJECTED`,
  `RULES_FALLBACK_STARTED/COMPLETED`). If even the fallback fails, a fixed
  manual-review stub (`R000`) applies — the system never performs an action.
- **Rules-mode independence.** `capstone.processor-mode=rules` needs no
  model, credential or provider; the `ChatModel` bean exists only when
  spring-ai mode is selected AND `spring.ai.anthropic.api-key` is set.

Recommendations expose `mode` (`RULES`/`SPRING_AI`/`RULES_FALLBACK`) and
`fallbackUsed` so reviewers can see exactly what produced them.

## Phase 4 Capabilities (current)

Everything from Phases 1–3, plus deterministic policy grounding (RAG)
behind the same processor boundary:

- **Synthetic policy corpus** (`src/main/resources/policies/*.md`) — nine
  Markdown policy documents with YAML-style front matter (`documentId`,
  `version`, `effectiveDate`, `status`, `category`). Includes superseded,
  future-effective and withdrawn variants so filtering is exercised.
- **Ingestion + chunking** (`rag.PolicyIngestionService`) — parses and
  validates metadata, rejects malformed/adversarial documents, chunks by
  `## ` sections, keeps stable chunk ids and preserves **exact passage
  text** (heading + body, verbatim) for citations.
- **Deterministic embeddings + vector store** — `DeterministicEmbeddingModel`
  (vocabulary-hashed, seed-free, zero external calls) backs a custom
  `SyntheticPolicyVectorStore` implementing the Spring AI `VectorStore`
  contract over the indexed policy chunks. No external vector database and
  no credentials required.
- **Bounded retrieval** (`rag.PolicyRetriever`) — builds the query from
  validated case facts + actual tool outcomes, runs top-K similarity
  search, then applies application-level filtering: score threshold
  (`retrieval.similarity-threshold`), top-K (`retrieval.top-k`), superseded
  versions and future-effective or withdrawn documents are excluded.
  Returns a typed `PolicyRetrievalOutcome`: `MATCHED` / `NO_MATCH` /
  `UNAVAILABLE`.
- **Grounded prompting** — retrieved passages are embedded in the user
  prompt inside an explicit `UNTRUSTED POLICY DATA` section; the system
  prompt allows the model to cite only supplied `chunkId`s, and treating
  any policy text as an instruction is forbidden.
- **Trusted citation resolution** — model-supplied citations resolve only
  against chunks retrieved *for the current case*: foreign, fabricated,
  altered or below-threshold citations are rejected, the recommendation
  falls back or is routed to manual review, and a
  `POLICY_CITATION_REJECTED` audit event is recorded.
- **Abstention.** `NO_MATCH` (or retrieval unavailable) in spring-ai mode
  routes to `MANUAL_REVIEW`/`INSUFFICIENT_EVIDENCE` — never invented
  policy. Rules mode adds rule `R005`: when a case needs policy grounding
  that isn't available, it explicitly escalates to manual review rather
  than guessing.
- **Persistence + API.** Citations persist with document id, title,
  version, effective date, section/chunk ids, score and the exact passage
  (`db/migration/V2__policy_citation_passage.sql`). Exposed via
  `GET /api/cases/{caseId}/citations` and embedded in recommendation
  responses.
- **Gated infrastructure.** `capstone.retrieval.enabled=false` removes all
  RAG beans; rules mode runs identically with zero embedding/vector
  infrastructure. All retrieval is audited
  (`POLICY_RETRIEVAL_STARTED/COMPLETED/FAILED` with bounded query hashes).

## Phase 5 Capabilities (current)

Everything from Phases 1–4, plus case-scoped conversational memory:

- **`POST /api/cases/{caseId}/messages`** — accepts an analyst follow-up
  for exactly one case. The `caseId` path parameter is the authoritative
  scope: the case is resolved first, and every memory read/write, evidence
  lookup and audit event uses only that id. There is no global, session or
  user-keyed memory anywhere in the code path.
- **Spring AI case-keyed memory.** `ChatMemory` is backed by a custom
  `CaseChatMemoryRepository` whose `conversationId` is the `caseId`.
  `findByConversationId` returns the bounded active window (the latest
  `memory.max-messages`, default 12), while the full record stays
  persisted; `saveAll` is merge-append and never truncates the record.
- **Immutable persisted history.** `db/migration/V3__case_message.sql`
  adds a `case_message` table (FK to `fraud_case`, case-local
  `sequence_number`, role, content, content hash, optional
  `client_reference` idempotency key, timestamps). Appends run under a
  per-case lock with the sequence assigned inside the same transaction, so
  concurrent submits can never collide or reorder; a retried
  `client_reference` replays the stored exchange instead of duplicating
  it. A fresh application context reconstructs each case's memory from
  this table only.
- **Grounded follow-up answers.** `SpringAiFollowUpAgent` answers from
  trusted context only: the persisted case, its evidence, the latest
  validated recommendation/citations, freshly retrieved policy passages
  and the untrusted conversation window. It may call the same allow-listed
  read-only evidence tools (bounded by `ai.max-tool-calls` /
  `ai.max-rounds`); citations resolve only against passages retrieved for
  this case. Foreign evidence/citation ids, wrong-case output, action
  claims, `humanReviewRequired=false` and prompt/system-prompt leaks are
  rejected — never persisted as answers. Rejection or provider failure
  degrades to a deterministic safe fallback answer (`answerSource`:
  `MODEL` / `SAFE_FALLBACK` / `STORED`), never an error that loses the
  received message.
- **Untrusted history, inert forever.** Conversation turns enter the
  prompt inside an explicit `untrusted_conversation_history` block;
  hostile persisted text survives restarts as plain data and can never
  become instructions, change scope, alter case state or lift review
  obligations.
- **Rules mode behaviour.** `capstone.processor-mode=rules` keeps all
  non-conversation operations working; the message endpoint returns a
  safe `409 CONVERSATION_UNAVAILABLE` — no fake answer is invented and no
  turn is persisted.
- **Audit + observability.** `CASE_MESSAGE_RECEIVED`,
  `CASE_MESSAGE_RESPONSE_CREATED`, `CASE_MEMORY_RECONSTRUCTED`,
  `CASE_MEMORY_LIMIT_APPLIED`, `CASE_MESSAGE_REJECTED`,
  `MODEL_INVOCATION_*` and `MODEL_OUTPUT_REJECTED` events are appended
  with bounded metadata (message id, sequence, hash prefix, counts) —
  never raw message content.

## Phase 6 Capabilities (current)

Everything from Phases 1–5, plus the mandatory human-review disposition
workflow. AI recommendations remain strictly advisory — only this workflow
records a case disposition, and it executes no banking action.

- **`POST /api/cases/{caseId}/reviews`** — typed review submission.
  Dispositions: `ESCALATE` (→ `ROUTED`), `REROUTE` (→ `ROUTED` with human
  `targetRoute`), `OVERRIDE` (→ `ROUTED` with human `targetRoute`),
  `REQUEST_MORE_INFORMATION` (→ `INCOMPLETE`, existing `ENRICHING` path
  applies later), `CLOSE` (→ `CLOSED`). Unknown dispositions fail at
  binding; the request cannot carry `reviewerId`, `reviewedAt`,
  `nextState` or recommendation fields (unknown JSON → 400).
- **Trusted reviewer identity.** `ReviewerIdentityProvider` resolves the
  principal; the default `SyntheticReviewerIdentityProvider` reads
  `capstone.review.actor-id`/`actor-role` (`TRAINING-REVIEWER-001` /
  `REVIEWER`). Client payloads cannot supply or spoof identity; only the
  `REVIEWER` role may disposition — anything else → 403 +
  `REVIEW_AUTHORIZATION_DENIED` audit.
- **Mandatory reason + acknowledgement checklist.** Blank/missing reason →
  400 (mandatory T12 scenario). Final dispositions must acknowledge
  evidence (`acknowledgeEvidenceReviewed`), policy citations where they
  exist (`acknowledgePolicyReviewed`), missing information
  (`acknowledgeMissingInformation`) and contradictions where surfaced
  (`acknowledgeContradictions`). When retrieval abstained, the policy gap
  is acknowledged via the missing-information flag — reviewers never fake
  a citation review.
- **Recommendation anchoring + optimistic concurrency.** The request's
  `recommendationId` must equal the case's latest validated recommendation
  (stale or foreign id → 409 `STALE_RECOMMENDATION`). `@Version` on
  `fraud_case` plus a unique constraint on
  `review_disposition.recommendation_id` guarantee only one disposition
  wins per recommendation — concurrent losers get 409 `REVIEW_CONFLICT`.
- **Idempotent retry.** `clientReference` (same convention as case
  messages) replays the stored disposition — no duplicate record,
  transition or audit event.
- **Immutable history.** `ReviewDisposition` rows are `@PreUpdate`/
  `@PreRemove`-guarded append-only records (`V4__phase6_review.sql`);
  ordering is deterministic (`recordedAt`, `reviewId`). The original
  recommendation is never rewritten — agent recommendation and human
  disposition stay separately traceable.
- **Read APIs.** `GET /api/cases/{caseId}/reviews` (deterministic history)
  and `GET /api/cases/{caseId}/review-context` (advisory recommendation,
  masked evidence summaries, exact policy passages, missing information,
  contradictions and the enforced acknowledgement checklist — never
  prompts, chain-of-thought or another case's data).
- **Audit.** `REVIEW_STARTED`, `REVIEW_SUBMITTED`, `REVIEW_REJECTED`,
  `REVIEW_AUTHORIZATION_DENIED` plus `CASE_STATE_CHANGED` — append-only,
  payload-hash summaries only.
- **Provider independence.** Review reads only persisted case/evidence/
  recommendation/citation data: provider, vector-store and memory outages
  do not block a human disposition. Nothing on the model/tool path can
  reach `ReviewService`.
- **State ownership.** Transitions run `READY_FOR_REVIEW → UNDER_REVIEW →
  ROUTED | CLOSED | INCOMPLETE` inside one application-owned transaction;
  `UNDER_REVIEW → INCOMPLETE` is the documented Phase 6 refinement for
  request-more-information. `CLOSED` remains terminal; `ROUTED`/`INCOMPLETE`
  reject further ordinary dispositions.

## Phase 7 Capabilities (current)

Everything from Phases 1–6, plus the six-screen **analyst workbench UI** —
a server-rendered Thymeleaf interface over the same application services.
It is an investigation and review assistant only: no banking,
customer-contact, refund, card-blocking, transfer, credit or filing action
exists anywhere in the UI.

### Routes

| Screen | URL |
|---|---|
| Case list (worklist) | `GET /ui/cases` |
| Case intake | `GET /ui/cases/new` (`POST /ui/cases`) |
| Case summary | `GET /ui/cases/{caseId}` |
| Investigation | `GET /ui/cases/{caseId}/investigation` (`POST …/investigate`) |
| Recommendation | `GET /ui/cases/{caseId}/recommendation` |
| Evidence | `GET /ui/cases/{caseId}/evidence` |
| Policy citations | `GET /ui/cases/{caseId}/policy` |
| Open passage | `GET /ui/cases/{caseId}/policy/citations/{citationId}` |
| Human review | `GET /ui/cases/{caseId}/review` (`POST …/review`) |

The root page (`/`) links into the workbench. The JSON API under
`/api/**` is unchanged.

### What the UI adds

- **Shared layout** — product/case header with case-state and processor
  (RULES / SPRING AI) badges, a persistent *synthetic training data*
  notice, left workflow navigation (Case → Investigation → Recommendation
  → Evidence → Policy → Human Review) and the main workspace.
- **Intake** — masked-identifier form with full server-side validation
  rendered inline; required synthetic-data and mandatory-review
  confirmations; unsafe markup and unknown fields rejected.
- **Investigation** — the bounded allow-listed tool plan, actual persisted
  tool traces (name/status/duration/trace id), distinct
  failure/timeout/unavailable states, missing information, retry where
  the state machine permits. Never chain-of-thought.
- **Recommendation** — classification, risk, route, confidence, processor
  source, concise rationale, evidence/policy links, missing information,
  and a permanent *advisory — human review required* label. The
  recommendation is read-only; a case-scoped follow-up thread is
  supporting functionality only.
- **Evidence** — customer claim separated from tool-generated evidence;
  device/location/transaction/merchant/prior-dispute/SLA sections with
  provenance and timestamps; explicit empty states, never fabricated
  items.
- **Policy citations** — top-K + threshold context, document id/title/
  version/effective-date/section/chunk id/score/exact passage, an
  **Open Passage** detail view, and explicit no-match/unavailable
  abstention states.
- **Human review** — advisory recommendation summary, read-only trusted
  reviewer identity (configuration identity seam, never a form field), all
  five dispositions, conditional evidence/policy/missing-info/contradiction
  acknowledgements, mandatory rationale (T12), REROUTE-only target route,
  audit preview, immutable history, and submit through `ReviewService`
  with all Phase 6 guarantees (state, stale recommendation, idempotency,
  optimistic concurrency, authorisation).

### Security model (UI)

- Spring Security supplies **CSRF protection** on browser form POSTs
  (`/ui/**`); `/api/**` is CSRF-exempt and behaves exactly as before.
- Authorisation stays in the application layer — `ReviewService` rejects
  non-REVIEWER actors regardless of what the UI renders.
- All untrusted text (customer statements, policy passages) renders
  through escaped Thymeleaf expressions only.
- No secrets, prompts, chain-of-thought or model internals are ever
  exposed.

See `docs/ui-guide.md`, `docs/ui-accessibility-checklist.md` and
`docs/phase-7-verification.md`.

## Phase 8 Capabilities (verification)

End-to-end validation of the T01–T12 suite (definitions in
`docs/phase-8-test-matrix.md`) — intake, deterministic investigation,
merchant-dispute and insufficient-evidence paths, policy grounding,
case-scoped memory, the full human-review workflow, failure/resiliency,
security (XSS/IDOR/injection/disclosure/CSRF), AI-agent safety and UI
regression. `Phase8EndToEndTest` codifies 14 of the scenarios against a
real application context; live evidence is recorded in
`docs/phase-8-verification.md`. One defect found and fixed:
unmatched/encoded API paths returned 500 instead of a safe 404
(`GlobalExceptionHandler` now maps `NoResourceFoundException`).

## Phase 9 Capabilities (current)

Everything from Phases 1–8, plus **observability and evaluation**:

- **Actuator** — `health`, `info`, `metrics` only; every sensitive
  endpoint stays unexposed (404). `ProcessorHealthIndicator` reports
  bounded config metadata; `show-details: never`.
- **Metrics** — `fraudcase.*` counters/timers for case intake,
  investigation lifecycle (started/completed/failed + duration by mode),
  per-tool invocations/failures/duration, AI request/failure/latency,
  review submitted/rejected, message answers. Tag values are small fixed
  sets only — never case/customer/transaction ids or user text.
- **Correlation** — `CorrelationIdFilter` propagates/generates
  `X-Request-Id` into MDC and echoes it on responses; `caseId` joins the
  MDC inside workflow entry points; the log pattern prints both.
- **Safe logging** — structured lifecycle events
  (`CASE_CREATED`, `INVESTIGATION_*`, `AI_REQUEST_FAILED`, `TOOL_FAILED`,
  `REVIEW_*`) carry bounded metadata only — no prompts, statements,
  tool payloads or secrets.
- **Evaluation** — `evaluation/` package scores deterministic
  business-control scenarios E01–E18 (functional/failure/security/
  isolation/human-review) on a documented 0/1/2 scale and
  `Phase9SpringAiEvaluationTest` covers the AI dimension E20–E25 with a
  scripted model. Reports land in `target/evaluation-report.json` and
  `target/evaluation-springai-report.json`. No LLM-as-judge.

## Phase 10 Capabilities (current)

Final readiness pass — a `GET /api/cases` summary list plus the
`/ui/cases` analyst worklist page (newest first, masked summaries only),
final gap/traceability documentation, clean reproducible build and a
repeatable demo script (`docs/DEMO.md`).
