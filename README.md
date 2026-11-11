# Intelligent Payment Fraud Investigation & Dispute Resolution Agent

An explainable, advisory assistant for payment fraud investigation and
dispute-resolution teams — built with **Spring Boot** and **Spring AI**
(primary AI framework, wired in a later phase).

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

No external services are required for Phase 2: persistence is an embedded H2
database initialised by Flyway migrations; there is no AI provider, vector
store or credential dependency.

## Technology Choices

| Component     | Selection                                     | Reason |
|---------------|-----------------------------------------------|--------|
| Spring Boot   | 3.5.16 (parent POM)                            | Stable, widely supported release line; fully compatible with Java 21 and already resolvable in this environment. |
| Spring AI     | BOM 1.1.8, imported only                       | Primary AI framework for later phases. The BOM pins the version centrally; **no `spring-ai-*` dependency is on the classpath yet**, keeping the domain framework-neutral. |
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
# compile + run all tests (Phase 1 + Phase 2)
mvn clean verify

# tests only
mvn test

# run locally (no credentials needed)
mvn spring-boot:run

# if port 8080 is already in use on your machine:
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8085
```

Then:

- `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`
- `POST http://localhost:8080/api/cases` → validate + capture a case
- `GET http://localhost:8080/api/cases/{caseId}` → case summary + state
- `POST http://localhost:8080/api/cases/{caseId}/investigations` → run the
  deterministic investigation (allowed from `NEW` and `INCOMPLETE`)
- `GET http://localhost:8080/api/cases/{caseId}/evidence` → collected evidence
- `GET http://localhost:8080/api/cases/{caseId}/tool-traces` → tool execution
  traces (hashed requests, redacted summaries)
- `GET http://localhost:8080/api/cases/{caseId}/recommendation` → latest
  validated recommendation

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
| `processor-mode` | `rules` | Processor implementation (`rules` now, `spring-ai` later). |
| `human-review-required` | `true` | Startup-enforced invariant. |
| `allow-financial-actions` | `false` | Startup-enforced invariant. |
| `allow-customer-contact` | `false` | Startup-enforced invariant. |
| `retrieval.top-k` | `4` | Future policy RAG top-K. |
| `retrieval.similarity-threshold` | `0.72` | Future policy RAG threshold. |
| `memory.max-messages` | `12` | Future bounded case memory. |
| `tools.timeout-ms` | `2500` | Per-tool timeout bound for evidence tool execution. |
| `tools.deterministic-seed` | `fraud-training-v1` | Deterministic evidence seed — same input → same evidence. |

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
│                     (orchestration), ToolExecutionService, AuditService
├── domain/           Framework-neutral model: case state machine,
│                     classification/route/risk enums, intake enums,
│                     FraudCase, FraudCaseContext, FraudCaseRecommendation,
│                     EvidenceItem, PolicyCitation, audit enums —
│                     NO Spring AI imports, ever
├── agent/
│   ├── common/       FraudCaseProcessor contract + RecommendationValidator
│   └── rules/        RulesFraudCaseAgent — deterministic R001–R008 engine
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

## Future Phases

`SpringAiFraudCaseAgent` (ChatClient/ChatModel adapter), policy RAG over a
Spring AI `VectorStore`, case-keyed memory, the human-review disposition
workflow, the six-screen UI, the T01–T12 scenario suite and evaluation
harness. See `docs/architecture.md` for the target boundaries.
