# Phase 3 Verification — Spring AI Adapter

Date: 2026-11-11 · Commit base: `d54da9e` (initial commit; Phase 3 changes in working tree)

Scope: verify the Phase 3 Spring AI adapter (`rules` + `spring-ai` processor
modes, approved tool calling, structured output, validation, deterministic
fallback, audit, prompt-injection baseline). Phase 1 and Phase 2 behaviour is
regression-tested, not re-verified in detail.

This document contains only the test-evidence matrix and defect log. Phase 4
scope (RAG, embeddings, VectorStore, memory, human-review workflow, UI) is
explicitly out of scope and was not implemented.

## Environment

| Item | Version |
|---|---|
| Java | OpenJDK 21.0.12.1 |
| Maven | 3.8.7 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 (BOM, `spring-ai.version` in `pom.xml`) |
| Provider artifact | `spring-ai-anthropic` 1.1.8 (raw artifact — no autoconfigure starter, by design) |
| AI client artifact | `spring-ai-client-chat` 1.1.8 |
| Test framework | JUnit 5 + AssertJ + Mockito (spring-boot-starter-test) |
| Database | H2 2.3 (embedded), Hibernate 6.6.53, `ddl-auto=validate` |
| Migration | Flyway (`V1__phase2_schema.sql` only; no Phase 3 DDL needed) |

`mvn dependency:tree` shows six `org.springframework.ai` artifacts, all at
exactly 1.1.8, no conflicting versions, no duplicate provider starters, no
vector-store/embedding artifacts. No credentials in build configuration —
the API key is read from `spring.ai.anthropic.api-key` (env placeholder) only
and only when `spring-ai` mode is configured.

## Build results

| Command | Result |
|---|---|
| `mvn clean test` | **195 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS** |
| `mvn clean package` | BUILD SUCCESS — `target/fraud-case-agent-0.1.0-SNAPSHOT.jar` (≈79 MB) |

## Requirement matrix

| # | Requirement | Implementation evidence | Test class/method | Actual result | PASS/FAIL |
|---|---|---|---|---|---|
| 1 | Spring AI dependency compatibility | `pom.xml` BOM `spring-ai.version=1.1.8`; raw artifacts only, no starter | `mvn dependency:tree` (manual) | 6 artifacts @1.1.8, zero conflicts; compatible with Boot 3.5.x | PASS |
| 2 | Adapter isolation (domain is AI-free) | Spring AI imports exist only in `agent/springai/` (4 files) and `config/SpringAiAdapterConfig` | grep scan of `src/main/java` | 0 Spring AI imports in domain/, persistence/, api/, agent/common/, agent/rules/ | PASS |
| 3 | `FraudCaseProcessor` implementation | `SpringAiFraudCaseAgent implements FraudCaseProcessor`; `RulesFraudCaseAgent implements FraudCaseProcessor`; both return `FraudCaseRecommendation` | `SpringAiFraudCaseAgentTest.validStructuredOutputMapsToDomainRecommendation`; `RulesFraudCaseAgentTest` (13 tests) | Both implement the contract; application services depend only on `FraudCaseProcessor`/`ProcessorSelector` | PASS |
| 4 | Processor selection (`rules`/`spring-ai`, unknown mode) | `ProcessorSelector` + `SpringAiModelGate` + `CapstoneProperties.ProcessorMode` (closed enum) | `ProcessorSelectorTest` (5 tests): `rulesModeSelectsRulesWithoutAnyModel`, `springAiModeWithModelSelectsAdapter`, `springAiModeWithoutModelFailsFast`, `onlyRulesAndSpringAiModesExist`, `unknownProcessorModeFailsConfigurationBinding` | rules→rules agent; spring-ai→adapter; spring-ai w/o model→`ProcessorModeUnavailableException` at startup; unknown mode→`BindException` (startup failure) | PASS |
| 5 | Rules-mode independence | `SpringAiAdapterConfig` creates `ChatModel` only under `capstone.processor-mode=spring-ai` AND `spring.ai.anthropic.api-key`; no autoconfigure starter on classpath | Live run: jar started with `ANTHROPIC_API_KEY` unset, `POST /api/cases` + `/investigations` | `mode=RULES`, `fallbackUsed=false`, UNAUTHORISED_TRANSACTION/HIGH/FRAUD_INVESTIGATION/0.85/R003/READY_FOR_REVIEW; startup log has 0 anthropic/chatmodel/spring.ai mentions | PASS |
| 6 | Offline default build/tests | `StubChatModel` test double; `@TestConfiguration` stub bean; no provider artifact wired without a key | Entire suite run with no credentials | 195/195 pass offline; no network calls (model is in-JVM stub) | PASS |
| 7 | System prompt content | `src/main/resources/prompts/fraud-case-system-prompt.st` | `SpringAiPromptBuilderTest.systemPromptContainsSafetyBoundariesAndFormat` + file inspection | Establishes assistant-not-decider, validated facts only, no invented evidence/tools/policy, no autonomous actions, humanReviewRequired always, INSUFFICIENT_EVIDENCE abstention, structured output only, concise rationale/no CoT, untrusted-tag handling, empty policyCitations, case-scoped tools | PASS |
| 8 | Untrusted data separation | `SpringAiPromptBuilder.userPrompt` wraps case facts/statement/evidence/tool outcomes in `<untrusted_*>` tags; system message holds only trusted instructions + format | `SpringAiPromptBuilderTest` (4 tests); `SpringAiPromptInjectionTest.hostileStatementStaysInUntrustedUserMessage` (6 statements) | Injection text appears only in user message inside untrusted tags, never in system prompt; truncation at 500 chars | PASS |
| 9 | Approved tool allow-list | `SpringAiToolAdapter.ALLOWED_TOOL_NAMES` — exactly the six evidence tools; explicit `FunctionToolCallback` construction, no `@Tool`/bean scanning | `SpringAiToolAdapterTest.exactlyTheSixAllowListedToolsAreRegistered` | Six and only six callbacks registered; fails if any unapproved tool is added | PASS |
| 10 | Bounded tool execution | Shared `AtomicInteger` budget (`capstone.ai.max-tool-calls=6`) → `LIMIT_EXCEEDED`; elapsed bound (`capstone.tools.timeout-ms`) → `TIMEOUT`; loop bound `capstone.ai.max-rounds=4` → `SpringAiProcessingException` | `SpringAiToolAdapterTest.toolCallBudgetIsEnforced`, `.exceededTimeoutIsReportedAsStructuredFailure`; `SpringAiFraudCaseAgentTest.neverEndingToolCallsHitTheRoundBound`; `SpringAiModeInvestigationTest.toolCallBudgetExceededIsBoundedAndCallsDoNotExecute`, `.neverEndingToolCallsHitRoundBoundThenFallBack` | 8 calls vs budget 6 → 2 blocked, 12 total traces (5+6+SLA); infinite loop → bounded → RULES_FALLBACK | PASS |
| 11 | Case-scoped tool execution | Every callback bound to the current case's masked refs; out-of-scope args → `INVALID_REQUEST` without executing | `SpringAiToolAdapterTest.foreignTransactionReferenceIsRejectedWithoutExecuting`, `.foreignCustomerReferenceIsRejected`; `SpringAiFraudCaseAgentTest.foreignToolCallDoesNotExecuteButLoopContinues`; `SpringAiModeInvestigationTest.crossCaseToolCallIsBlockedAndNoForeignDataLeaks` | Case-B args produce no execution, no trace, no evidence leak | PASS |
| 12 | Structured output | `BeanOutputConverter<AiRecommendationOutput>` + `toDomain` mapping to `FraudCaseRecommendation`; not prose parsing | `SpringAiFraudCaseAgentTest.validStructuredOutputMapsToDomainRecommendation`, `.malformedJsonIsRejected`, `.emptyModelOutputIsRejected` | JSON→DTO→domain record; malformed/empty output → `InvalidModelOutputException` | PASS |
| 13 | Enum validation | `parseEnum` per field → `InvalidModelOutputException` on unknown values; null mandatory fields → domain contract → wrapped as invalid output | `SpringAiFraudCaseAgentTest.unknownEnumValueIsRejected`, `.unknownRouteAndRiskValuesAreRejected`, `.missingMandatoryFieldIsRejected` | Unknown classification/risk/route rejected; missing classification rejected | PASS |
| 14 | RecommendationValidator | Mode-aware `validate()`: caseId match, enums non-null, confidence∈[0,1], humanReviewRequired, case-local evidence/trace ids, rule-id legitimacy, empty citations, bounded non-claiming rationale | `RecommendationValidatorTest` v01–v18 (18 tests) | All 17 checklist conditions covered; violations never persist | PASS |
| 15 | Mandatory human review | Domain record rejects `humanReviewRequired=false`; validator double-checks; startup `@AssertTrue` config | `SpringAiFraudCaseAgentTest.humanReviewRequiredFalseIsRejected`; `SpringAiModeInvestigationTest.humanReviewFalseOutputIsRejectedThenFallsBack`; `RecommendationValidatorTest.v01` | Rejected → `MODEL_OUTPUT_REJECTED` → RULES_FALLBACK with `humanReviewRequired=true` | PASS |
| 16 | Foreign-case rejection (wrong caseId) | `toDomain` rejects a non-blank model `caseId` ≠ investigated case | `SpringAiFraudCaseAgentTest.wrongCaseIdIsRejected`, `.missingCaseIdIsBoundToTheInvestigatedCase`; `SpringAiModeInvestigationTest.wrongCaseIdIsRejectedThenFallsBack` | Foreign caseId → `InvalidModelOutputException` → fallback; persisted rec always carries the investigated caseId | PASS |
| 17 | Foreign evidence/trace rejection | Validator checks ids against case-local persisted sets | `RecommendationValidatorTest.v06`, `.v07`; `SpringAiModeInvestigationTest.foreignEvidenceIdsAreRejectedThenFallsBack`, `.foreignToolTraceIdsAreRejectedThenFallsBack` | Foreign ids → `MODEL_OUTPUT_REJECTED` → fallback | PASS |
| 18 | Fake-citation rejection (no RAG) | `toDomain` rejects any non-empty `policyCitations`; validator rejects citations in every mode; mapper always stores `List.of()` | `SpringAiFraudCaseAgentTest.fabricatedPolicyCitationsAreRejected`; `SpringAiModeInvestigationTest.fabricatedPolicyCitationsAreRejectedThenFallsBack`; `RecommendationValidatorTest.v13` | Invented citation → rejected → fallback; 0 citation rows persisted | PASS |
| 19 | Rule-ID protection | `AiRecommendationOutput` has no `ruleIds` field (model cannot claim rules); SPRING_AI validator rejects non-empty ruleIds | `SpringAiModeInvestigationTest.ruleIdsClaimedByModelAreRejected`; `RecommendationValidatorTest.v12`, `.v15` | Persisted SPRING_AI recs have empty ruleIds; RULES_FALLBACK accepts only known rule ids (+R000) | PASS |
| 20 | Provider fallback (F01–F04) | `InvestigationService` catches processor failure → `MODEL_INVOCATION_FAILED` → rules → `RULES_FALLBACK_STARTED/COMPLETED` → persist `RULES_FALLBACK` | `SpringAiModeInvestigationTest.providerFailureFallsBackToRulesAndAudits`, `.providerTimeoutFallsBackToRules`, `.providerAuthFailureFallsBackToRules`, `.providerRateLimitFallsBackToRules` | All four failure classes → RULES_FALLBACK + full audit chain | PASS |
| 21 | Malformed-output fallback (F05–F08) | `InvalidModelOutputException` → `MODEL_OUTPUT_REJECTED` → rules fallback | `SpringAiModeInvestigationTest.malformedOutputFallsBackAndIsNeverPersisted`, `.missingMandatoryFieldIsRejectedThenFallsBack`; agent tests F05–F08 | Malformed/non-JSON/missing-field/unknown-enum → rejected → fallback; invalid output never persisted | PASS |
| 22 | Persistence | `RecommendationEntity.mode` = SPRING_AI / RULES / RULES_FALLBACK; evidence/trace/citation relations; invalid output never persisted | `SpringAiModeInvestigationTest` persistence assertions; `PersistenceIntegrationTest` (11 tests) | Mode column populated per path; foreign/cited-invalid rows absent | PASS |
| 23 | Auditability | Append-only `audit_event` rows via `AuditService`/`AuditEventAppender`; payload stored as hash only | `SpringAiModeInvestigationTest` audit-type assertions | Events: `MODEL_INVOCATION_STARTED/COMPLETED/FAILED`, `MODEL_OUTPUT_REJECTED`, `RULES_FALLBACK_STARTED/COMPLETED`, `RECOMMENDATION_CREATED`; hashed payloads, no raw prompts/responses/keys | PASS |
| 24 | API safety | `InvestigationResponses` projections expose ids/enums/rationale only — no prompt, CoT, key, provider internals | `SpringAiPromptInjectionTest` response-body assertions; `InvestigationApiTest` (9 tests, Phase 2 regression) | Response contains no system prompt, no untrusted tags, no injected statement text, no provider details | PASS |
| 25 | Prompt-injection baseline | Untrusted-tag separation; output contract enforcement; action-claim rationale rejection; prompt-leak marker rejection | `SpringAiPromptInjectionTest` (12 tests: 6 separation + 6 compliance) | Statements A–F stay data; compliant-model variants all rejected → fallback; no action ever executes | PASS |
| 26 | Sensitive-data scan | — | grep scans: Spring AI imports, secrets patterns, forbidden capabilities, RAG artifacts, surefire-reports leakage | No keys/tokens/real PANs (only env placeholders + negative-test PANs); no forbidden capability implementations; no VectorStore/embedding/memory; 0 raw prompt/model text in test output | PASS |
| 27 | Phase 1 regression | Intake validation, masking, consent, health, state machine | `FraudCaseIntakeRequestValidationTest`, `FraudCaseControllerTest`, `CaseStateTransitionTest`, `FraudCaseAgentApplicationTests`, config tests | All green (part of 195) | PASS |
| 28 | Phase 2 regression | Flyway/JPA, deterministic tools, evidence/traces, audit append, rules agent, state transitions, rules-mode API | `PersistenceIntegrationTest` (11), `DeterministicToolsTest` (26), `ToolExecutionServiceTest` (4), `RulesFraudCaseAgentTest` (13), `InvestigationApiTest` (9), `RecommendationValidatorTest` (18), `CaseStateTransitionTest` (11) | All green (part of 195); deterministic outcomes unchanged | PASS |

## Fallback test matrix (F01–F15)

| ID | Scenario | Test evidence | Result |
|---|---|---|---|
| F01 | Provider unavailable | `providerFailureFallsBackToRulesAndAudits` | RULES_FALLBACK + MODEL_INVOCATION_FAILED — PASS |
| F02 | Provider timeout | `providerTimeoutFallsBackToRules` (ResourceAccessException+SocketTimeoutException) | RULES_FALLBACK — PASS |
| F03 | Auth/config failure | `providerAuthFailureFallsBackToRules` (HTTP 401) | RULES_FALLBACK — PASS |
| F04 | Rate limit | `providerRateLimitFallsBackToRules` (HTTP 429) | RULES_FALLBACK — PASS |
| F05 | Malformed output | `malformedOutputFallsBackAndIsNeverPersisted` | MODEL_OUTPUT_REJECTED → fallback — PASS |
| F06 | Non-JSON output | `SpringAiFraudCaseAgentTest.malformedJsonIsRejected` + F05 e2e | Rejected → fallback — PASS |
| F07 | Missing mandatory field | `missingMandatoryFieldIsRejected(ThanFallsBack)` | Rejected → fallback — PASS |
| F08 | Unknown enum | `unknownEnumValueIsRejected`, `unknownRouteAndRiskValuesAreRejected` | Rejected — PASS |
| F09 | Wrong caseId | `wrongCaseIdIsRejected(ThenFallsBack)` | Rejected → fallback — PASS |
| F10 | humanReviewRequired=false | `humanReviewRequiredFalseIsRejected`, `humanReviewFalseOutputIsRejectedThenFallsBack` | Rejected → fallback — PASS |
| F11 | Foreign evidence ID | `foreignEvidenceIdsAreRejectedThenFallsBack` + validator v06 | Rejected → fallback — PASS |
| F12 | Foreign ToolTrace ID | `foreignToolTraceIdsAreRejectedThenFallsBack` + validator v07 | Rejected → fallback — PASS |
| F13 | Invented policy citation | `fabricatedPolicyCitationsAreRejected(ThenFallsBack)` + v13 | Rejected → fallback — PASS |
| F14 | Unsupported action claim | `rationaleClaimingExecutedActionIsRejectedThenFallsBack` + v16 | Rejected → fallback — PASS |
| F15 | Tool-call limit exceeded | `toolCallBudgetExceededIsBoundedAndCallsDoNotExecute`, `neverEndingToolCallsHitRoundBoundThenFallBack`, adapter/agent bounds tests | Bounded; excess calls never execute; loop stops → fallback — PASS |

For every F-scenario the same invariants hold: the invalid AI output is never
persisted as approved, no raw provider response or stack trace reaches the
API (the POST returns the persisted fallback recommendation with
`mode=RULES_FALLBACK`, `fallbackUsed=true` implied by mode), the fallback is
validated by `RecommendationValidator`, and `humanReviewRequired=true`.

## Fallback-failure path (§25)

`SpringAiModeInvestigationTest.aiFailureWithUnfindableTransactionYieldsInsufficientEvidence`:
case uses `TXN-4040-404040` (pinned NOT_FOUND fixture) + provider outage →
rules fallback applies R001 → persisted `RULES_FALLBACK` recommendation:
`INSUFFICIENT_EVIDENCE`, `MANUAL_REVIEW`, `humanReviewRequired=true`,
non-empty `missingInformation`, case state `INCOMPLETE`. No stronger result
is fabricated; the fixed `R000` manual-review stub remains the last resort if
even the fallback fails validation.

## Standard synthetic case (§26)

| Field | Rules mode (live jar) | Spring AI mode (stub) |
|---|---|---|
| mode | RULES | SPRING_AI |
| state | READY_FOR_REVIEW | READY_FOR_REVIEW |
| tools executed | 6 | 5 + model calls + SLA |
| classification | UNAUTHORISED_TRANSACTION | UNAUTHORISED_TRANSACTION |
| risk / route | HIGH / FRAUD_INVESTIGATION | HIGH / FRAUD_INVESTIGATION |
| confidence | 0.85 | 0.8 (stubbed) |
| missingInformation | "transaction authentication outcome unavailable" | "check auth logs" |
| ruleIds | [R003] | [] |
| policyCitations | [] | [] |
| humanReviewRequired | true | true |
| fallbackUsed | false | false |

Authentication outcome is never invented: `authOutcome=UNAVAILABLE` is
carried into `missingInformation`; `authenticationOutcomeIsNeverInvented`
asserts the persisted recommendation never claims success or failure.

## Defects found and fixed during verification

1. **Wrong-case output silently re-scoped.** `toDomain` overwrote the model's
   `caseId` with the context caseId, so output naming another case was
   accepted instead of rejected. Fixed: a non-blank mismatched `caseId` now
   throws `InvalidModelOutputException`. Regression:
   `SpringAiFraudCaseAgentTest.wrongCaseIdIsRejected`,
   `SpringAiModeInvestigationTest.wrongCaseIdIsRejectedThenFallsBack`.
2. **Missing mandatory field mis-classified as invocation failure.** A null
   mandatory field raised `NullPointerException` (record `requireNonNull`),
   escaping the `IllegalArgumentException` catch → audited
   `MODEL_INVOCATION_FAILED` instead of `MODEL_OUTPUT_REJECTED`. Fixed by
   catching `NullPointerException` in the domain-contract wrapper.
   Regression: `missingMandatoryFieldIsRejected(ThenFallsBack)`.
3. **System-prompt leakage in rationale unchecked.** A model regurgitating
   prompt material into `rationale` was not rejected. Fixed: distinctive
   prompt markers in rationale → `InvalidModelOutputException`. Regression:
   `rationaleContainingSystemPromptMaterialIsRejected`,
   `injectedPromptLeakIsRejected`.
4. **No explicit success audit for model invocation.** Added
   `MODEL_INVOCATION_COMPLETED` (audit payload: `classification|route` only,
   hashed). Regression: `validModelOutputPersistsAsSpringAiMode`,
   `validStructuredOutputMapsToDomainRecommendation`.

## Known limitations (documented, not defects)

- `spring-ai` mode without a configured model fails startup fast (explicit
  operator configuration honored rather than silently degraded); runtime
  failures fall back to rules.
- Tool timeout is a measured bound around synchronous in-process execution —
  it reports `TIMEOUT` rather than preempting; deterministic tools run
  sub-millisecond.
- The action-claim rationale filter is deliberately conservative — a false
  positive routes to the safe deterministic fallback.
- Audit payloads are hashes by design (`AuditService` stores
  `payload_hash`); event types + ordering provide the trail.
