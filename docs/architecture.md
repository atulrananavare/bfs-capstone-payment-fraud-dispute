# Architecture — Fraud Case Agent

Spring Boot + **Spring AI** (selected primary AI framework). The domain is
deliberately framework-neutral: `com.example.fraudcase.domain` contains no
Spring AI (or any AI-framework) imports. Provider-specific code only ever
lives behind the adapter boundary.

```
┌─────────────────────────────────────────────────────────────────┐
│ EXPERIENCE                                                      │
│  REST API (api/) · Thymeleaf UI (later phases)                  │
│  intake · workspace · evidence/citation/recommendation display  │
├─────────────────────────────────────────────────────────────────┤
│ APPLICATION                                                     │
│  FraudCaseService · InvestigationService · ToolExecutionService │
│  AuditService · processor selection · state transitions         │
├─────────────────────────────────────────────────────────────────┤
│ DOMAIN                                                          │
│  FraudCase · ClaimFacts · TransactionFacts · CustomerFacts      │
│  FraudCaseRecommendation · FraudCaseContext · PolicyCitation    │
│  EvidenceItem · ToolOutcome · audit enums                       │
│  CaseState machine · classification/route/risk/intake enums     │
│  invariants — framework-neutral, no AI imports                  │
├───────────────────────┬─────────────────────────────────────────┤
│ RULES ADAPTER (now)   │ AI ADAPTER                              │
│ RulesFraudCaseAgent   │ SpringAiFraudCaseAgent (later)          │
│ deterministic R001-8  │ prompts · ChatClient/ChatModel · tools  │
│ fallback · rule IDs   │ · memory · retrieval · typed output     │
├───────────────────────┴─────────────────────────────────────────┤
│ TOOLS (now)            │ KNOWLEDGE                              │
│ 6 allow-listed,        │ PolicyIngestionService · Retriever     │
│ read-only synthetic    │ chunk → embed → VectorStore →          │
│ evidence tools         │ threshold → cite → abstain (all later) │
├──────────────────────────┴──────────────────────────────────────┤
│ PERSISTENCE (now)                                               │
│  Flyway V1 schema · Spring Data JPA repositories: cases,        │
│  claims, evidence, tool traces, recommendations, citations,     │
│  reviews, append-only audit events                              │
├─────────────────────────────────────────────────────────────────┤
│ OBSERVABILITY                                                   │
│  correlation ID · latency · status · metrics · redacted logs    │
│  Micrometer/OpenTelemetry (later) · safe /actuator/health (now) │
└─────────────────────────────────────────────────────────────────┘
```

## Boundary Details

### Experience layer
`api/` holds the REST surface. `FraudCaseIntakeRequest` is a pure contract:
validation is declarative (Jakarta + custom validators), and server-managed
fields (`caseId`, `analystId`) are not part of it — clients cannot set them.
Unknown JSON properties are rejected. Errors are structured and safe; stack
traces, provider details and echoed sensitive values are never returned.

### Application layer
`FraudCaseService` owns server-managed fields and coordinates validation →
domain object → persistence. `InvestigationService` is the Phase 2
orchestrator: it loads the persistent case, executes every allow-listed
tool through `ToolExecutionService` (which persists a `ToolTrace` per
execution), stores evidence idempotently, runs the selected
`FraudCaseProcessor`, validates the recommendation through
`RecommendationValidator`, persists it and records audit events through
`AuditService`. State changes only happen through
`FraudCase.transitionTo`, which enforces the `CaseState` rules — an LLM
can never move a case between states.

### Domain layer
Everything the business agrees on, with zero AI dependencies: the case
state machine (`NEW → ENRICHING → INCOMPLETE/READY_FOR_REVIEW →
UNDER_REVIEW → ROUTED/CLOSED`), classification (`UNAUTHORISED_TRANSACTION`
… `GENUINE_TRANSACTION`), route (`FRAUD_INVESTIGATION` … `MANUAL_REVIEW`),
risk (`LOW/MEDIUM/HIGH`), intake enums, and the recommendation contract
with hard invariants (`humanReviewRequired == true`, `0 ≤ confidence ≤ 1`).

### Processor contract
`agent/common/FraudCaseProcessor#analyze(FraudCaseContext) →
FraudCaseRecommendation` is the single seam every analysis implementation
must satisfy — same domain model, same output schema, same invariants.

### Rules adapter (implemented)
`RulesFraudCaseAgent` — the deterministic `FraudCaseProcessor`. Ordered
rules R001–R008 evaluate claim + evidence and produce classification,
route, risk, confidence, missing information, contradictions and a
rationale naming every firing rule and the facts that triggered it. It
works with no model, provider, embedding or vector dependency; it never
invents an authentication outcome (`authOutcome=UNAVAILABLE` becomes
`missingInformation`, never a claimed success/failure). Its output is
always passed through `RecommendationValidator` before persistence.

### Spring AI adapter (later)
`SpringAiFraudCaseAgent` — prompts as resources, `ChatClient`/`ChatModel`,
typed tool methods, `VectorStore` retrieval, case-keyed memory/advisor,
Micrometer observations. System role: *"a payment-fraud and dispute triage
assistant, not a final financial decision maker."* It may only use
validated case facts, returned tool evidence, retrieved synthetic policy
and approved case context — and must abstain rather than fabricate.
Exact APIs will be verified against `spring-ai.version` (1.1.8).

### Tools boundary (implemented — evidence tools)
Explicitly allow-listed, read-only synthetic evidence tools in `tools/`:
`getTransactionDetails`, `getDeviceSignals`, `compareRecentLocations`,
`getMerchantRiskProfile`, `getPriorDisputes`, `calculateCaseSla`. Each has
a typed request/result, argument validation (masked-ref hygiene enforced),
pinned training fixtures plus seeded hash derivation via `Determinism`,
provenance and structured statuses (`SUCCESS`, `NOT_FOUND`,
`INVALID_REQUEST`). No financial or customer-impacting tool can exist.
`retrievePolicyEvidence` (RAG-backed) and `saveAuditEvent` arrive in later
phases; Phase 2 audit writes go through the internal `AuditService` +
restricted `AuditEventAppender`.

### Knowledge boundary (later)
`PolicyIngestionService` validates synthetic-policy metadata, chunks by
meaningful headings, embeds and stores in a Spring AI `VectorStore`. The
retriever applies `retrieval.top-k`/`similarity-threshold`, filters stale
versions, injects only relevant passages, returns exact citation metadata —
and abstains when nothing qualifies. Policy is never invented.

### Persistence boundary
Phase 2 replaces the in-memory placeholder with Flyway-managed JPA
persistence on embedded H2 (`MODE=LEGACY`, in-memory — still zero external
services). `V1__phase2_schema.sql` creates 12 tables (fraud_case,
transaction_claim, evidence_item, tool_trace, recommendation +
4 collection tables, policy_citation, review_disposition, audit_event)
with FKs and indexes; Hibernate runs `ddl-auto=validate` so migration
drift fails fast. `JpaFraudCaseStore` + `FraudCaseMapper` translate
between domain objects and entities — the domain never sees JPA. The
`AuditEventAppender` repository deliberately exposes no update/delete
methods and `AuditEventEntity` is `@PreUpdate`/`@PreRemove`-guarded and
immutable; audit rows store SHA-256 `payload_hash` of the redacted
payload, never raw sensitive values.

### Observability boundary
Every investigation will carry a correlation ID through application →
tools → retrieval → model. Tool name/status/duration/redacted summaries and
retrieval scores are recorded; raw prompts/responses and sensitive values
are not logged. Phase 1 exposes only `/actuator/health` with
`show-details: never` — no database, provider or vector store is required
for the app to be healthy at this stage.

### Human-review boundary
Recommendations are advisory. `humanReviewRequired=true` is enforced in the
domain contract (`FraudCaseRecommendation` rejects `false`) and in startup
configuration. Final disposition is recorded by an authorised reviewer in a
later phase; no agent output can bypass it.
