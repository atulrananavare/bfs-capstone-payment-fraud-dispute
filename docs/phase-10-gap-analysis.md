# Phase 10 — Final Gap Analysis & Traceability

Verified against the running application, the automated suite (410+ tests)
and the live Phase 8/9 evidence. Statuses: IMPLEMENTED / PARTIAL /
MISSING / NOT APPLICABLE / BLOCKED.

## Requirement gap matrix

| Requirement | Status | Evidence | Gap | Severity | Action |
|---|---|---|---|---|---|
| Case creation/management | IMPLEMENTED | `POST /api/cases`, `FraudCaseService`, T01, `Phase8EndToEndTest` | — | — | — |
| Analyst worklist (view all cases) | IMPLEMENTED | `GET /api/cases`, `GET /ui/cases`, `Phase10CaseListTest` | was MISSING → fixed this phase | was LOW | `listCases()` + `cases/list.html` + home link |
| Deterministic fraud investigation (R001–R008) | IMPLEMENTED | `RulesFraudCaseAgent`, T02–T04, rules tests | — | — | — |
| Spring AI integration behind `FraudCaseProcessor` | IMPLEMENTED | `SpringAiFraudCaseAgent`, `SpringAiModelGate`, stub-model tests, live 401→`RULES_FALLBACK` | — | — | — |
| Allow-listed read-only tools + bounded calls | IMPLEMENTED | 6 tools, `ToolExecutionService`, `SpringAiToolAdapter.ALLOWED_TOOL_NAMES`, budget tests | — | — | — |
| Policy grounding (RAG, citations, abstention) | IMPLEMENTED | `PolicyRetriever`, `SyntheticPolicyVectorStore`, `DeterministicEmbeddingModel`, T05, R005 path | matched-retrieval needs a working embedding path (synthetic store is deterministic) | — | — |
| Case-scoped conversational memory | IMPLEMENTED | `CaseMessageService`, V3 schema, T06, E24, isolation tests | — | — | — |
| Mandatory human review (5 dispositions) | IMPLEMENTED | `ReviewService`, T07–T12, E06–E11/E18 | — | — | — |
| Protected final actions absent | IMPLEMENTED | no financial/customer-action code paths; startup safety flags | — | — | — |
| Failure handling & safe errors | IMPLEMENTED | `GlobalExceptionHandler` (+NoResourceFound 404 fix), T04, S3 suite, E14/E15 | — | — | — |
| Security controls (validation/XSS/CSRF/IDOR/injection) | IMPLEMENTED | validators, `SecurityConfig`, S4 suite, E05/E16 | — | — | — |
| Thymeleaf UI | IMPLEMENTED | 8 screens + worklist, escaped output, CSRF forms, a11y | — | — | — |
| Observability (actuator/metrics/logging/correlation) | IMPLEMENTED | Phase 9 — `CaseMetrics`, `CorrelationIdFilter`, `ProcessorHealthIndicator`, `Phase9ObservabilityTest` | — | — | — |
| AI evaluation framework | IMPLEMENTED | `evaluation/`, E01–E18 + E20–E25, JSON reports | — | — | — |
| Token-usage metrics | BLOCKED | no usage payload available (stub/401'd provider) | reported, not fabricated | LOW | document limitation |
| Real reviewer authentication | NOT APPLICABLE | synthetic `ReviewerIdentityProvider` seam by design (capstone) | — | — | — |
| Live-provider e2e | BLOCKED | env key returns Anthropic 401; fallback verified instead | external credential | LOW | document limitation |

## T01–T12 traceability matrix

| ID | Requirement | Implementation | Automated test | Live/E2E evidence | Result |
|----|---|---|---|---|---|
| T01 | Intake + validation | `FraudCaseController`/`Service`, DTO validators | `Phase8EndToEndTest` + intake tests | `phase-8-verification.md` — 201/NEW + 400 rejects | PASS |
| T02 | Full investigation | `InvestigationService` + 6 tools + `RulesFraudCaseAgent` | `Phase8EndToEndTest`, service tests | R003, 6 tools, READY_FOR_REVIEW | PASS |
| T03 | Merchant-dispute flow | R004 path | `Phase8EndToEndTest`, eval E03 | MERCHANT_DISPUTE → DISPUTE_OPERATIONS | PASS |
| T04 | Insufficient evidence | tool NOT_FOUND + R001 | `Phase8EndToEndTest`, eval E04 | INCOMPLETE + missing info | PASS |
| T05 | Policy grounding | `PolicyRetriever` + citations + R005 abstention | retrieval/grounding tests | RETRIEVED/NOT_REQUESTED verified | PASS |
| T06 | Case-scoped memory | `CaseMessageService` + V3 rows + ChatMemory | isolation + conversation tests, eval E24 | disjoint histories, 404/409 | PASS |
| T07 | Approve disposition | `ReviewService` ESCALATE | review tests, eval E06 | ESCALATE → ROUTED | PASS |
| T08 | Alternative dispositions | RMI/REROUTE branches | review tests, eval E10 | INCOMPLETE / ROUTED / 400 | PASS |
| T09 | CLOSE + terminality | state gate | review tests, eval E07 | CLOSED, further → 409 | PASS |
| T10 | Review integrity | stale-id + idempotency + locking | review tests, eval E08/E18 | 409 / single-row replay | PASS |
| T11 | Identity spoofing/authorisation | `ReviewerIdentityProvider` + strict DTO | auth tests, `Phase8EndToEndTest` | 400 spoofed / 403 role | PASS |
| T12 | Mandatory reason | bean validation + service guard | `hr02` suite, eval E09 | 400, nothing persisted | PASS |

## Defects/gaps closed in Phase 10

- **G-10-01 (LOW):** no way to see all cases — added read-only
  `GET /api/cases` + `/ui/cases` worklist + home link; `Phase10CaseListTest`.
- **Docs:** README completed through Phase 10, `docs/TESTING.md`,
  `docs/DEMO.md`, this matrix; `application-example.yml` aligned with the
  Phase 9 actuator surface.

## Remaining (documented, not defects)

- Token-usage metrics unavailable (provider returns no usage payload).
- Live Anthropic call ends 401 in this environment — provider path
  exercised via stub + safe fallback instead.
- R005/R008 not covered by live probes — covered by deterministic tests.
