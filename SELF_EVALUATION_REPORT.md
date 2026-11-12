# Capstone Self-Evaluation Report

- Participant: **Atul Rananavare**
- Evaluation date: 2026-11-12

## 1. Executive Summary

- Project: `fraud-case-agent` — Intelligent Payment Fraud Investigation & Dispute
  Resolution Agent (advisory assistant; human review owns every final decision)
- Primary framework: **Spring AI 1.1.8** (`ChatModel`, `ToolCallback`,
  `ChatMemoryRepository`, `VectorStore`, `EmbeddingModel`, Anthropic provider)
- Secondary framework: None — LangChain4j is not present in `pom.xml` or source
- Build status: **PASS** — `mvn clean verify` → `fraud-case-agent-0.1.0-SNAPSHOT.jar`
- Test status: **PASS** — 413 tests, 0 failures, 0 errors, 0 skipped
- Overall score: **96/100**
- Percentage: **96%**
- Readiness: **Demo Ready**
- Scoring model used: **Spring AI only** — metric 4 evaluated out of 20
  (metric 5's 10 points redistributed); metric 5 marked *Optional Not
  Implemented*. Total remains 100.

## 2. Repository and Technology Overview

| Area | Finding | Evidence |
|---|---|---|
| Application / objective | Payment-fraud & dispute case investigation assistant producing advisory recommendations; final disposition always belongs to a human reviewer | `FraudCaseAgentApplication.java`, `README.md`, `AGENTS.md` |
| Java / Spring Boot | Java 21 / Spring Boot 3.5.16 | `pom.xml` (`spring-boot-starter-parent` 3.5.16) |
| Build tool | Maven (system `mvn`; no wrapper script present) | `pom.xml` |
| AI framework | Spring AI only — `spring-ai-client-chat`, `spring-ai-anthropic`, `spring-ai-vector-store` pinned via `spring-ai-bom` | `pom.xml` (`spring-ai.version=1.1.8`); no LangChain4j dependency or import exists |
| Model / provider config | Anthropic `ChatModel` auto-configured by starter; credentials via env vars; deterministic `RulesFraudCaseAgent` is the default mode | `application.yml`, `application-example.yml`, `SpringAiModelGate` |
| Database / persistence | Embedded H2 + Spring Data JPA; Flyway owns DDL (`V1`–`V5` migrations); Hibernate `ddl-auto=validate` | `src/main/resources/db/migration/`, `application.yml` |
| Vector DB / embedding | `SyntheticPolicyVectorStore implements org.springframework.ai.vectorstore.VectorStore` backed by `DeterministicEmbeddingModel implements EmbeddingModel` — deterministic, in-repo, no external vector service required | `rag/SyntheticPolicyVectorStore.java`, `rag/DeterministicEmbeddingModel.java` |
| UI | Server-rendered Thymeleaf analyst workbench (`/ui/**`) + static CSS | `templates/`, `ui/CaseIntakeUiController.java`, `ui/CaseUiController.java` |
| MCP / external integration | None present | Repository scan |
| Entry point | `com.example.fraudcase.FraudCaseAgentApplication` | `src/main/java/.../FraudCaseAgentApplication.java` |
| Framework-neutral interfaces | `FraudCaseProcessor` (agent port), `PolicyRetriever`, `EvidenceTool`, `CaseConversationStore`, `AuditService` | `agent/common/FraudCaseProcessor.java`, `rag/`, `tools/`, `memory/` |
| Configuration modes | `capstone.agent.mode=rules` (default, no credentials) / `=spring-ai` (requires `ChatModel` + Anthropic key). No `langchain4j` mode | `CapstoneProperties`, `application.yml`, `ProcessorSelector` |

## 3. Build and Test Results

| Command | Result | Details |
|---|---|---|
| `mvn clean verify` | PASS | BUILD SUCCESS; 166 main + ~50 test source files compile clean; jar packaged |
| Test suite | PASS | **Tests run: 413, Failures: 0, Errors: 0, Skipped: 0** |
| `Phase8EndToEndTest` | PASS | T01–T12 end-to-end regression pack, 14/14 |
| `Phase9EvaluationTest` | PASS | Deterministic evaluation dataset E01–E18, 18/18, score-2 pass rate 100% (`target/evaluation-report.json`) |
| `Phase9SpringAiEvaluationTest` | PASS | Scripted-model AI evaluations E20–E25, 6/6 (`target/evaluation-springai-report.json`) — no live provider required |
| `Phase9ObservabilityTest` | PASS | 8/8 — metrics, health, correlation ID, safe-logging probes |
| `Phase10CaseListTest` | PASS | 3/3 — case-list API + UI |
| Coverage tool | Not configured | No JaCoCo/coverage plugin in `pom.xml` — reported, not fabricated |
| Compile warnings | None blocking | Clean compile |
| External-credential failures | None | Entire suite runs credential-free (rules mode + scripted `StubChatModel`) |

## 4. Evaluation Scorecard

| # | Metric | Maximum | Score | Status | Evaluator Comments | Evidence |
|---|---|---:|---:|---|---|---|
| 1 | BFS fraud and dispute use case | 10 | 10 | Implemented | Full fraud/dispute case study: masked synthetic intake (`CUST-••••`, `••••73` merchants), amount/timestamp/channel/device/claim facts, `NEW→UNDER_INVESTIGATION→READY_FOR_REVIEW→{ROUTED,CLOSED,INCOMPLETE}` lifecycle, all four recommendation enums + `DUPLICATE_PROCESSING`/`MERCHANT_DISPUTE`, separation of fraud (`FRAUD_INVESTIGATION`), dispute (`DISPUTE_OPERATIONS`), duplicate (`PAYMENT_OPERATIONS`) and manual review routing. Zero loan-underwriting concepts (repo-wide grep clean). | `domain/CaseState`, `CaseClassification`, `RecommendedRoute`, `IssueType`; `FraudCaseController` |
| 2 | Architecture and maintainability | 12 | 12 | Implemented | Layered packages (`api/application/domain/tools/rag/memory/persistence/agent/ui/config/observability/evaluation`); `domain/` imports no AI/JPA types; provider code isolated behind `FraudCaseProcessor`; mode selected by `capstone.agent.mode`; single rules engine + single `RecommendationValidator` shared by both processors — no duplicated rules across adapters; Bean Validation + `GlobalExceptionHandler`; typed enums throughout | `FraudCaseProcessor`, `ProcessorSelector`, `RulesFraudCaseAgent`, `SpringAiFraudCaseAgent`, `RecommendationValidator` |
| 3 | Fraud investigation workflow and deterministic tools | 15 | 13 | Implemented | Six deterministic, seed-stable evidence tools covering transaction lookup, device signals, geolocation/impossible-travel, merchant risk, prior disputes, and SLA calculation. Typed `ToolRequest`/`ToolResult` schemas; statuses `SUCCESS/NOT_FOUND/INVALID_REQUEST/UNAVAILABLE`; provenance + persisted `ToolTraceEntity`; tool failure → `INSUFFICIENT_EVIDENCE`/missing-info, never fabricated. **Deduction: no transaction-velocity calculation tool and no sanctions/PEP screening tool** (rubric example list); policy lookup is a retrieval pipeline, not an agent tool. | `tools/*Tool.java`, `Determinism`, `DeterministicToolSupport`, `ToolExecutionService`, `persistence/ToolTraceEntity` |
| 4 | Spring AI implementation | 20 | 19 | Implemented | `ChatModel.call` with native `ToolCallback` allow-list loop (bounded iterations); prompt builders treat customer text/policy as untrusted data; structured output via `AiRecommendationOutput`/`AiFollowUpOutput` + `RecommendationValidator` — invalid output rejected, never persisted; `CaseChatMemoryRepository implements ChatMemoryRepository` keyed by caseId with bounded window; `VectorStore` + `EmbeddingModel` implementations with ingestion service; provider config externalized (`application.yml` + env); provider outage → deterministic fallback, review path unaffected. Minor deduction: token-usage metrics unavailable on the stub path; no advisor-based orchestration (custom orchestration used instead). | `SpringAiFraudCaseAgent`, `SpringAiFollowUpAgent`, `SpringAiToolAdapter`, `SpringAiPromptBuilder`, `SpringAiModelGate`, `SpringAiAdapterConfig` |
| 5 | LangChain4j implementation | — | — | Optional Not Implemented | LangChain4j absent from `pom.xml` and all sources; its 10 points redistributed to metric 4 per the framework scoring rule | `pom.xml` dependency list |
| 6 | RAG, embeddings, vector retrieval, and citations | 15 | 14 | Implemented | 9 synthetic policy docs (CNP dispute, duplicate, escalation, evidence, human-review, merchant-dispute, legacy); front-matter metadata (id/title/version/section/status/effectiveDate/owner); parsing + chunking (9 files → 35 passages); deterministic embeddings + `VectorStore` similarity search; configurable `capstone.retrieval.{enabled,topK=4,similarityThreshold=0.72}`; `Status.CURRENT`-only catalog excludes SUPERSEDED/WITHDRAWN; grounded citations persist to `PolicyCitationEntity` and render in UI; explicit abstention/no-match state (`UiPolicyAbstentionTest`, `PolicyContextStatus`). Deduction: rules-mode recommendations carry no citations by design (honest abstention — `// never faked`), so citation value appears only via the AI/retrieval path rather than every recommendation. | `rag/` (10 classes), `PolicyIngestionService`, `PolicyRetriever`, `PolicyRetrieverTest`, `SpringAiRagGroundingTest`, `templates/cases/citations.html` |
| 7 | Structured output, memory, and human review | 8 | 8 | Implemented | Typed `FraudCaseRecommendation` + `AiRecommendationOutput`; fixed classification/risk enums; risk factors, evidenceIds, missingInformation, rationale, confidence, citations, ruleIds; schema validation before persistence (`RecommendationValidator`, `InvalidModelOutputException`); bounded memory `capstone.memory.max-messages=12`; strict caseId isolation; four human-review dispositions (`CONFIRM`, `ESCALATE`, `REROUTE`, `CLOSE`) with mandatory reason, server-side reviewer identity, immutable recommendation + separately stored disposition; no autonomous adverse action exists in code | `domain/FraudCaseRecommendation`, `ReviewService`, `ReviewDispositionType`, `CaseChatMemoryRepository`, `ReviewApiTest` |
| 8 | Security, privacy, responsible AI, and auditability | 8 | 8 | Implemented | No secrets in repo (scan clean — `sk-`/key hits are validator rejection fixtures); synthetic-only data enforced by validators (`MaskedIdentifier`, `NoSensitiveContent`, `NoUnsafeMarkup`, `IsoCurrency`); customer text treated as data (injection cannot alter rules/state — verified live and in E16/E23); allow-listed read-only evidence tools (only write-capable path is audit append); CSRF on browser forms, escaped `th:text` only, no `th:utext` on untrusted data; actuator surface limited to `health,info,metrics` (`/actuator/env` → 404 verified); `X-Request-Id` correlation filter + MDC; append-only audit events with bounded metadata/hashes — no raw prompts, statements or secrets; model/framework/mode recorded in audit metadata; responsible-AI limitations documented in README/AGENTS | `api/validation/`, `CorrelationIdFilter`, `AuditEventAppender`, `SecurityConfig`, `application.yml` |
| 9 | Testing, reliability, and observability | 8 | 8 | Implemented | 413 deterministic tests: tool unit tests, boundary/negative, controller/validation, structured-output parsing, RAG grounding/no-answer/retired-policy, tool+model+retrieval failure paths, memory isolation, `FraudCaseProcessor` contract tests, UI regression, prompt-injection, idempotent replay (optimistic locking + unique constraint + `clientReference` idempotency key), plus Phase 8 T01–T12 E2E pack and Phase 9 evaluation harness (24/24). Micrometer `fraudcase.*` metrics (cases, investigations, tools, ai, reviews, messages), correlation IDs, health indicator — verified live | `src/test/**` (50 files), `observability/`, `evaluation/`, surefire summary |
| 10 | UI, documentation, and developer experience | 4 | 4 | Implemented | Thymeleaf workbench: worklist (`/ui/cases`), intake, investigation, recommendation, evidence, citations, human-review screens; explicit loading/success/failure/empty/abstention states; labelled controls, landmarks, focus, AA contrast, responsive; README + `docs/{architecture,TESTING,DEMO,phase-8..10}.md` with prerequisites/config/run/test/limitations; no underwriting terminology | `templates/`, `static/css/app.css`, `README.md`, `docs/` |
|  | **Total** | **100** | **96** | | | |

## 5. Framework Evaluation

### 5.1 Spring AI

Verifiable Spring AI integration behind the `FraudCaseProcessor` port:

- `ChatModel` resolved via `ObjectProvider` (`SpringAiModelGate` fails closed
  when absent); `model.call(Prompt)` with `ToolCallback` list
- `SpringAiToolAdapter` maps the six `EvidenceTool`s to Spring AI tool
  callbacks — allow-listed names only, bounded call loop
- Structured output: `AiRecommendationOutput`/`AiFollowUpOutput` parsed via
  `BeanOutputConverter`, then re-validated by the framework-neutral
  `RecommendationValidator` — `caseId` mismatch, unknown enums, foreign
  evidence ids, `humanReviewRequired=false` and action claims are all
  rejected; nothing invalid is persisted
- Memory: `CaseChatMemoryRepository implements ChatMemoryRepository`, window
  `capstone.memory.max-messages`, caseId-only keying
- RAG: `SyntheticPolicyVectorStore implements VectorStore`,
  `DeterministicEmbeddingModel implements EmbeddingModel`,
  `PolicyIngestionService` (9 files/35 passages), `PolicyRetriever` with
  top-K + similarity threshold + CURRENT-only filter
- Provider config fully externalized; provider outage → deterministic
  fallback recommendation (`RULES_FALLBACK`), never a persisted failure
- Metrics: `fraudcase.ai.{requests,failures,latency}` plus Spring AI's
  built-in `gen_ai.*` observations via Micrometer

Evidence: `agent/springai/*`, `SpringAiFraudCaseAgentTest`,
`SpringAiToolAdapterTest`, `SpringAiRagGroundingTest`,
`SpringAiPromptInjectionTest`, `Phase9SpringAiEvaluationTest` (E20–E25).

### 5.2 LangChain4j

Not implemented — no dependency, imports or configuration. Scored as
*Optional Not Implemented* with points redistributed to Spring AI per the
rubric.

### 5.3 Framework Parity

Not applicable — single framework.

## 6. Acceptance Scenario Results

| Scenario | Mode | Expected | Actual | Result | Evidence |
|---|---|---|---|---|---|
| Known device, familiar merchant, normal amount | rules | Genuine / low-risk advisory | `GENUINE_TRANSACTION` + `MANUAL_REVIEW` + LOW (R008) | PASS | `RulesFraudCaseAgentTest#r08_genuineOutcome` |
| New device, unusual location, amount ≫ baseline | rules | Suspicious/unauthorised with cited evidence | `UNAUTHORISED_TRANSACTION` + HIGH + R003, routed `FRAUD_INVESTIGATION`, 3 evidence ids + tool traces + provenance strings ("device not previously associated", "distanceKm=1450") | PASS (executed live) | Live run `FC-3850B012` + `TXN-7777-123456` on :8090; `InvestigationApiTest#*R003`; `r03_*` unit tests |
| Five or more transactions within 10 minutes | — | Velocity indicator recorded | **No velocity tool or signal exists** — `TransactionDetailsTool` returns a single transaction; no count/window attribute | NOT IMPLEMENTED | Repo scan of `tools/`, `domain/` — zero velocity references |
| Confirmed synthetic screening match | — | Mandatory escalation, not autonomous | **No sanctions/PEP screening tool exists**; escalation exists only via human review disposition `ESCALATE` | NOT IMPLEMENTED | Tool list: `tools/` contains 6 evidence tools, none screening |
| Evidence service unavailable | rules | `INSUFFICIENT_EVIDENCE` / more info | `INSUFFICIENT_EVIDENCE` + MEDIUM + R001 + `MANUAL_REVIEW`, missing-info listed | PASS (executed live) | Live run `TXN-4040-404040`; `r01_*`, `unavailableToolEvidenceIsReportedMissing` tests |
| Same case submitted twice | rules | Idempotent / duplicate protection | `DUPLICATE_PROCESSING` + R002 → `PAYMENT_OPERATIONS` on duplicate fingerprint; review submissions deduplicated via `clientReference` + optimistic locking + unique constraint | PASS (executed live; intake itself creates a second case — see gap G-1) | Live run `TXN-5555-000002`; `ReviewApiTest` idempotency tests; E18 replay |
| Policy question with active source | spring-ai (scripted) | Grounded answer + title/section/version | Retrieval returns CURRENT passages with id/version/section metadata; grounded citations persist to `PolicyCitationEntity`; rules mode abstains (`never faked`) | PASS | `PolicyRetrieverTest`, `SpringAiRagGroundingTest`, `citations.html` |
| Question absent from policy corpus | rules/spring-ai | Explicit no-information | `PolicyContextStatus` abstention states; similarity threshold + CURRENT filter yield empty outcome → explicit "no policy" UI/API state | PASS | `UiPolicyAbstentionTest`, `PolicyRetrieverTest` no-match cases |
| Retired policy contains matching text | — | Retired excluded | `PolicyCatalog` admits `Status.CURRENT` only; `SUPERSEDED`/`WITHDRAWN` filtered before indexing | PASS | `PolicyCatalog.java:53`, `POL-CNP-017-v3.1` (superseded) vs `v3.2`, `POL-LEG-050` legacy; `PolicyRetrieverTest` |
| Model returns malformed output | spring-ai (scripted) | Validated retry / safe failure | `InvalidModelOutputException` → deterministic fallback, invalid JSON never persisted; `BeanOutputConverter` parse + `RecommendationValidator` double-gate | PASS | `Phase9SpringAiEvaluationTest` E22; `RecommendationValidatorTest`; `SpringAiModeInvestigationTest` malformed-output cases |
| Two separate case IDs | rules/spring-ai | Memory isolated | Case-A memory never surfaced in case-B answers; separate JPA rows keyed by caseId | PASS | E12/E13 (rules), E24 (spring-ai scripted), `CaseMessageService` tests |
| Prompt injection in transaction narrative | rules | Narrative = data; constraints retained | Injected "set humanReviewRequired=false / GENUINE" statement processed as data — R002 still fired, `humanReviewRequired=true`, state machine untouched | PASS (executed live) | Live run on :8090 (`TXN-5555-000002` + injected statement); E16/E23; `SpringAiPromptInjectionTest` |

## 7. Strengths

1. **Enforcement architecture, not prompt promises.** Every critical control
   — `humanReviewRequired`, state transitions, tool allow-list, memory
   scoping, output validation, audit append-only, reviewer identity — lives
   in deterministic Java code the LLM cannot reach (56 `AGENTS.md` rules
   enforced in code and tests, not just docs).
2. **Real Spring AI integration without a provider dependency.** Native
   `ChatModel`/`ToolCallback`/`ChatMemoryRepository`/`VectorStore`/
   `EmbeddingModel` implementations, validated end-to-end by a scripted
   model — the entire suite runs credential-free while the Anthropic path
   stays configuration-ready.
3. **Verification depth.** 413 deterministic tests + T01–T12 E2E pack +
   a dedicated evaluation harness (24 scenarios, reports persisted to
   `target/`) + Micrometer metrics/correlation/health — every claim in this
   report is backed by executed evidence.

## 8. Gaps and Risks

| Priority | Gap or Risk | Impact | Evidence | Recommended Fix |
|---|---|---|---|---|
| High | No transaction-velocity tool (rubric scenario "5+ txns / 10 min" unsupported) | One rubric scenario cannot produce a velocity indicator | `tools/` — 6 tools, none computes count/window | Add `TransactionVelocityTool` to the allow-list (deterministic seed; count txns per customer/device in a window); record as evidence item |
| High | No synthetic sanctions/PEP screening tool | "Confirmed screening match → mandatory escalation" scenario unsupported | `tools/` | Add `ScreeningTool` returning deterministic match/no-match; wire a rule (e.g., R009) forcing `ESCALATE` routing, still human-approved |
| Medium | Case intake has no client idempotency key — a retried `POST /api/cases` creates a second case (review submissions are idempotent; duplicate *transaction* detection exists at investigation) | Duplicate cases possible on client retry | `FraudCaseController` — no `clientReference` on intake | Accept optional `clientReference` on intake; unique constraint + replay existing case |
| Medium | Rules-mode recommendations carry no policy citations (honest abstention — `// never faked`); citation value only via retrieval/spring-ai path | RAG value less visible in default mode | `RulesFraudCaseAgent` line ~310 | Optional: run deterministic `PolicyRetriever` in rules mode and attach real retrieved citations (keeps "never fabricate" intact) |
| Low | No coverage tool configured (JaCoCo absent) | Coverage percentage unavailable | `pom.xml` | Add `jacoco-maven-plugin` if coverage reporting is required |
| Low | Token-usage metrics unavailable on the scripted-model path | AI-cost observability partial | `Phase9ObservabilityTest` notes | Record `Usage` from real provider responses when spring-ai mode runs against Anthropic |
| Low | `fraudcase.investigations.failed` counts in-service failures only; unknown-case investigate 404s in the controller pre-check | Slightly narrower failure metric semantics | `InvestigationController#getCase` pre-check | Document (done) or move lookup inside service |

## 9. Security and Responsible AI Findings

| Severity | Finding | File or Component | Remediation |
|---|---|---|---|
| Info | No secrets, keys, tokens or real customer data in repository; `sk-`/`PRIVATE KEY` scan hits are validator rejection fixtures only | `CaseMessageValidationTest`, `NoSensitiveContentValidator` | None — keep exclusions in `.gitignore` |
| Info | Untrusted narrative/policy text can never reach instruction layer; injection verified to not alter rules/state/humanReviewRequired | `SpringAiPromptBuilder`, live + E16/E23 | None |
| Info | No autonomous financial/customer-contact capability exists anywhere — the only write-capable tool path is audit append | `tools/`, `AGENTS.md` rules 4–6 | None — keep allow-list frozen |
| Info | Audit stores bounded metadata/hashes; no raw prompts, statements or secrets persisted or logged | `AuditEventAppender`, `CorrelationIdFilter`, `application.yml` logging | None |
| Low | Intake endpoint accepts duplicate submissions (no idempotency key) | `FraudCaseController` | Add `clientReference` + unique constraint |
| Low | Actuator exposes `metrics` names/values (bounded tags only); acceptable for demo, consider restricting in production | `application.yml` | Reduce `management.endpoints.web.exposure.include` in prod profile |

## 10. Recommended Improvements

### Critical
- None — no critical defects found.

### High
1. Add `TransactionVelocityTool` (deterministic, seed-stable) to satisfy the
   velocity acceptance scenario.
2. Add a synthetic `ScreeningTool` (sanctions/PEP-style) wired to mandatory
   `ESCALATE` routing through the human-review path.

### Medium
3. Intake idempotency via optional `clientReference` on `POST /api/cases`.
4. Attach real retrieved policy citations in rules mode via
   `PolicyRetriever` (preserves the never-fabricate rule while surfacing RAG
   value in the default mode).

### Low
5. Add JaCoCo for coverage reporting.
6. Record provider token usage when a real `ChatModel` response carries it.
7. Narrow `management.endpoints.web.exposure.include` for a production
   profile.

## 11. Definition-of-Done Checklist

- [x] Fraud case intake works with synthetic data
- [x] Deterministic tools provide traceable evidence
- [x] Structured recommendations are validated
- [x] Policy answers are grounded and cited
- [x] Human review is mandatory
- [x] Conversation/case memory is isolated
- [x] Secrets are not stored in the repository
- [x] Error and no-information states are handled safely
- [x] Automated tests pass
- [x] README enables another participant to run the project

## 12. Final Verdict

**Demo Ready.**

The capstone is a complete, verifiable advisory fraud/dispute
investigation system. Every scored claim was checked against source and
executed evidence: `mvn clean verify` → 413/413 tests; T01–T12 E2E pack;
24/24 evaluation scenarios; live API verification of R001/R002/R003
outcomes, prompt-injection resistance, correlation IDs, metrics and health
on a running instance. The only rubric scenarios not implemented are two
tool categories that were never built (velocity calculation, sanctions/PEP
screening) — reported honestly rather than fabricated — and neither
weakens any safety boundary, since both would route through the same
deterministic, human-approved path as every other capability. With the two
tool additions the project covers the full rubric scope.
