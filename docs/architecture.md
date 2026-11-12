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
│ RULES ADAPTER (now)   │ AI ADAPTER (now — Phase 3)              │
│ RulesFraudCaseAgent   │ SpringAiFraudCaseAgent                  │
│ deterministic R001-8  │ system prompt · ChatModel · case-bound  │
│ fallback · rule IDs   │ tool callbacks · typed output · bounded │
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

### Spring AI adapter (implemented — Phase 3)
`SpringAiFraudCaseAgent` (`agent.springai` — the only package importing
Spring AI chat types, on Spring AI 1.1.8). The trusted system prompt is a
classpath resource plus the structured-output format contract; all case
content is untrusted data inside tagged sections of the user message.
Exactly the six allow-listed tools are registered as
`FunctionToolCallback`s bound to the current case: arguments must equal the
case's own masked references, a shared `capstone.ai.max-tool-calls` budget
applies, executions go through `ToolExecutionService` (real traces +
persisted evidence + audit) and an elapsed bound reports `TIMEOUT` to the
model. The tool-call loop is owned by the agent
(`internalToolExecutionEnabled=false`) and bounded by
`capstone.ai.max-rounds`. Structured output is bound to
`AiRecommendationOutput` (which deliberately lacks `ruleIds`) and mapped to
`FraudCaseRecommendation`; malformed JSON, unknown enums, bad confidence,
fabricated citations or broken invariants raise
`InvalidModelOutputException`, provider failures raise
`SpringAiProcessingException`. `InvestigationService` validates every AI
recommendation via `RecommendationValidator` (SPRING_AI mode: no rule ids,
no citations, case-local ids only) and on any failure falls back to the
rules processor persisted as `RULES_FALLBACK` — audited as
`MODEL_INVOCATION_*` / `MODEL_OUTPUT_REJECTED` / `RULES_FALLBACK_*`. The
`ChatModel` bean exists only when `processor-mode=spring-ai` AND a provider
key is configured; selecting spring-ai without a model fails startup.
Case memory remains for a later phase.

### Tools boundary (implemented — evidence tools)
Explicitly allow-listed, read-only synthetic evidence tools in `tools/`:
`getTransactionDetails`, `getDeviceSignals`, `compareRecentLocations`,
`getMerchantRiskProfile`, `getPriorDisputes`, `calculateCaseSla`. Each has
a typed request/result, argument validation (masked-ref hygiene enforced),
pinned training fixtures plus seeded hash derivation via `Determinism`,
provenance and structured statuses (`SUCCESS`, `NOT_FOUND`,
`INVALID_REQUEST`). No financial or customer-impacting tool can exist.
`retrievePolicyEvidence` is satisfied in Phase 4 by the deterministic
`PolicyRetriever` path below; `saveAuditEvent` stays an internal service —
audit writes go through `AuditService` + restricted `AuditEventAppender`.

### Knowledge boundary (implemented — Phase 4)
Synthetic policy documents (`src/main/resources/policies/*.md`) carry
front-matter metadata (`documentId`/`version`/`effectiveDate`/`status`/
`category`). `PolicyIngestionService` validates metadata, rejects
malformed documents, chunks by `## ` sections with stable chunk ids and
preserves exact passage text. Chunks are embedded by
`DeterministicEmbeddingModel` (vocabulary-hashed, zero external calls)
into `SyntheticPolicyVectorStore`, a Spring AI `VectorStore` backed by the
indexed catalog — no external vector service. `PolicyRetriever` builds the
query from validated case facts + actual tool outcomes, runs top-K
similarity search, then filters: score ≥ `retrieval.similarity-threshold`,
top-K bound, superseded versions and future-effective/withdrawn documents
excluded — returning `MATCHED`/`NO_MATCH`/`UNAVAILABLE`. Retrieved
passages enter the prompt inside an `UNTRUSTED POLICY DATA` section; the
model may cite only supplied chunk ids, citations resolve only against the
current case's retrieved set (foreign/fabricated/altered/below-threshold
citations → `POLICY_CITATION_REJECTED`), and `NO_MATCH` routes to manual
review — policy is never invented. `capstone.retrieval.enabled=false`
removes all RAG beans; rules mode needs none of it.

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
payload, never raw sensitive values. `V2__policy_citation_passage.sql`
(Phase 4) adds policy-citation columns (`title`, `effective_date`,
`section_id`, `chunk_id`, `score`, `passage`) so every citation persists
the exact verbatim passage it references.

### Observability boundary
Every investigation will carry a correlation ID through application →
tools → retrieval → model. Tool name/status/duration/redacted summaries and
retrieval scores are recorded; raw prompts/responses and sensitive values
are not logged. Phase 1 exposes only `/actuator/health` with
`show-details: never` — no database, provider or vector store is required
for the app to be healthy at this stage.

### Human-review boundary (implemented — Phase 6)
Recommendations are advisory; `humanReviewRequired=true` is enforced in the
domain contract and startup configuration. `POST /api/cases/{id}/reviews`
records a typed human disposition (`ESCALATE`/`REROUTE`/`OVERRIDE` →
`ROUTED`, `REQUEST_MORE_INFORMATION` → `INCOMPLETE`, `CLOSE` → `CLOSED`)
via `ReviewService`. Reviewer identity/role resolve from
`ReviewerIdentityProvider` (synthetic `capstone.review.*` seam until real
auth); only `REVIEWER` may disposition. Reason is mandatory; evidence,
policy, missing-information and contradiction acknowledgements are
enforced per the review context. Requests must reference the current
recommendation (stale/foreign → 409); `@Version` + a unique constraint on
`recommendation_id` make concurrent submissions safe; `clientReference`
gives idempotent replay. `ReviewDisposition` rows are immutable
(`@PreUpdate`/`@PreRemove`), audited (`REVIEW_*`), and never imply a
banking action — disposition is a workflow record only. `UNDER_REVIEW →
INCOMPLETE` is the sole Phase 6 state-machine refinement.
