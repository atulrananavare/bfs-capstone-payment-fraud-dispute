# Testing Strategy — Fraud Case Agent

All tests are deterministic. The default suite needs **no** AI provider,
credential, vector store or external service — external model behavior is
exercised through the scripted `StubChatModel`.

## Commands

```bash
mvn test                          # full suite
mvn clean verify                  # clean build + full suite
mvn test -Dtest=Phase8EndToEndTest        # T01–T12 e2e regression (14 checks)
mvn test -Dtest=Phase9EvaluationTest      # deterministic evaluation E01–E18
mvn test -Dtest=Phase9SpringAiEvaluationTest  # AI-dimension evaluation E20–E25
mvn test -Dtest=Phase9ObservabilityTest   # actuator/metrics/correlation
mvn test -Dtest=Phase10CaseListTest       # analyst worklist gap fix
```

## Layers

| Layer | Examples | What they pin |
|---|---|---|
| Unit | `RulesFraudCaseAgent` tests, `RecommendationValidator`, tool tests | R001–R008 outcomes, output validation, deterministic fixtures |
| Service | `InvestigationService`, `ReviewService`, `CaseMessageService`, `ToolExecutionService` tests | state machine, idempotency, case-scoped memory, bounded tool calls |
| API (MockMvc) | `FraudCaseController*`, `ReviewController*`, `Phase8EndToEndTest`, `Phase9ObservabilityTest`, `Phase10CaseListTest` | status codes, safe error shapes, hidden-field rejection, CSRF, correlation headers |
| Persistence | repository/store tests | Flyway-validated schema, append-only audit/review, case-local sequence |
| UI (MockMvc + Thymeleaf) | `UiSpringAiTest`, Phase 8 UI probes | all screens render, escaped output, CSRF on forms, advisory labels |
| AI safety | `SpringAiPromptInjectionTest`, `SpringAiModeInvestigationTest`, `SpringAiRagGroundingTest` | stub-scripted model output: grounded persistence, rejection of unsafe output, `RULES_FALLBACK` on failure, injection treated as data |
| AI parity | `LangChain4jFraudCaseAgentTest`, `LangChain4jModeInvestigationTest`, `ProcessorSelectorTest` | same contract on the LangChain4j adapter (`StubLc4jChatModel`): valid/malformed output, tool-call loop, `RULES_FALLBACK`, `LANGCHAIN4J` mode persistence, fail-fast without a model, unselected provider never required |
| Evaluation | `CaseEvaluationRunner` + two drivers | E01–E18 deterministic checks + E20–E25 AI leg, 0/1/2 scoring, JSON reports under `target/` |

## T01–T12

Defined in `docs/phase-8-test-matrix.md` (T12 keeps its canonical
mandatory-reason meaning). Live execution evidence:
`docs/phase-8-verification.md`. Automated coverage:
`Phase8EndToEndTest` (14 checks) plus the dedicated review/memory/security
suites.

## Security & failure coverage

XSS/unsafe markup rejection, oversized/hidden fields, malformed JSON,
unknown ids, IDOR-style case access, CSRF on browser POSTs (JSON API
exempt), prompt injection as data, review spoofing (payload identity,
non-REVIEWER role, blank reason, stale recommendation, duplicate replay),
double investigation, terminal CLOSED state, safe error bodies without
stack traces or internals.

## Observability checks

`Phase9ObservabilityTest` verifies actuator exposure (sensitive endpoints
404), counter/timer behavior on real flows, low-cardinality safe tags and
`X-Request-Id` propagation.
