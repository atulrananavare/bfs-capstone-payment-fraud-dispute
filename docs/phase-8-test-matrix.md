# Phase 8 — T01–T12 End-to-End Test Matrix

T01–T12 have no canonical definitions in this repository except **T12**
(the mandatory-reason review scenario, defined in `phase-6-verification.md`
and `ui-guide.md`). T12 keeps that exact meaning. The remaining scenarios
are defined here as the end-to-end suite covering the implemented
capability surface (Phases 1–7), mapped to real components.

Execution environment: app on `:8085` (`capstone.processor-mode=rules`,
H2 + Flyway) and `:8086` (`spring-ai` mode where the provider is
reachable). Automated coverage cited alongside live evidence.

| ID | Scenario / business purpose | Preconditions | Steps | Expected result | Components exercised |
|----|------------------------------|---------------|-------|-----------------|----------------------|
| T01 | Case intake happy path + validation | app up | `POST /api/cases` valid payload; malformed variants | 201 `caseId` + state `NEW`, persisted, `CASE_CREATED` audit; malformed → 400 safe error | `FraudCaseController`, validators, `FraudCaseService`, `AuditService`, JPA/Flyway |
| T02 | Full deterministic investigation | T01 case in `NEW` | `POST /api/cases/{id}/investigations` | 201; 6 allow-listed tools executed + traces persisted; evidence recorded; recommendation `UNAUTHORISED_TRANSACTION`/HIGH → `FRAUD_INVESTIGATION` (R003); state `READY_FOR_REVIEW`; `humanReviewRequired=true` | `InvestigationService`, `ToolExecutionService`, tools ×6, `RulesFraudCaseAgent`, `RecommendationValidator`, `EvidenceRecorder` |
| T03 | Merchant-dispute flow | case w/ `merchantRecognised=RECOGNISED`, `issueType=AMOUNT_DISPUTE` | intake + investigate | `MERCHANT_DISPUTE` → `DISPUTE_OPERATIONS` (R004), MEDIUM; READY_FOR_REVIEW | claim-driven rule path, same pipeline as T02 |
| T04 | Insufficient-evidence path | case whose `transactionRef` resolves NOT_FOUND in `TransactionDetailsTool` | intake + investigate | `INSUFFICIENT_EVIDENCE` → `MANUAL_REVIEW` (R001), `missingInformation` populated; state `INCOMPLETE`; re-investigation allowed from INCOMPLETE | deterministic tool failure surface, R001, state machine |
| T05 | Policy grounding (RAG) | retrieval enabled | investigate; `GET /api/cases/{id}/citations`; inspect audit | `POLICY_RETRIEVAL_*` audit events with bounded query hash; MATCHED → passages feed prompt; NO_MATCH/UNAVAILABLE → R005/abstention + missing-info gap; citations exact-passage, case-scoped only | `PolicyIngestionService`, `PolicyRetriever`, `SyntheticPolicyVectorStore`, `DeterministicEmbeddingModel`, `PolicyCitation`, `RulesFraudCaseAgent.applyR005` |
| T06 | Case-scoped conversation memory | spring-ai mode (model reachable) else stub | `POST /api/cases/{A|B}/messages`; `GET` history | caseId path is the only memory key; windowed view (max 12), full record persisted; Case A ↔ Case B never mix; rules mode → 409 `CONVERSATION_UNAVAILABLE`; foreign evidence/citation ids rejected | `CaseMessageService`, `CaseChatMemoryRepository`, `CaseConversationStore`, `SpringAiFollowUpAgent`, `V3__case_message.sql` |
| T07 | Human review — approve | case READY_FOR_REVIEW | `POST /reviews` ESCALATE + reason + acks | 201; `ReviewDisposition` appended; state → `UNDER_REVIEW`→`ROUTED`; rec immutable; audit `REVIEW_STARTED/SUBMITTED`, `CASE_STATE_CHANGED` | `ReviewService`, `ReviewController`, `ReviewerIdentityProvider`, `CaseState`, `ReviewDispositionEntity` |
| T08 | Human review — alternative dispositions | READY_FOR_REVIEW | REQUEST_MORE_INFORMATION; REROUTE w/ `targetRoute` | RMI → `INCOMPLETE` (re-investigation path preserved); REROUTE → `ROUTED` + human `targetRoute`, invalid/missing/identical route rejected | `ReviewService` disposition branches |
| T09 | Human review — CLOSE + terminality | READY_FOR_REVIEW | CLOSE; then submit another review | 201 → `CLOSED`; further reviews → 409 `REVIEW_NOT_PERMITTED`; CLOSED is terminal | state gate, disposition validation |
| T10 | Review integrity — stale/duplicate/concurrent | READY_FOR_REVIEW | wrong `recommendationId`; replay `clientReference`; concurrent submits | stale/foreign id → 409 `STALE_RECOMMENDATION`; replay returns stored disposition (single row/audit); concurrent → exactly one 201 (`@Version` + unique `recommendation_id`) | idempotency, optimistic locking, unique constraint |
| T11 | Identity spoofing + authorisation | READY_FOR_REVIEW | payload w/ `reviewerId`/`reviewedAt`/`nextState`; non-REVIEWER actor | unknown fields → 400 (no disposition); non-REVIEWER role → 403 `REVIEW_FORBIDDEN` + `REVIEW_AUTHORIZATION_DENIED` audit | `ReviewRequest` strict binding, `ReviewerPrincipal.mayDisposition`, `SyntheticReviewerIdentityProvider` |
| T12 | Mandatory reason (canonical) | READY_FOR_REVIEW | OVERRIDE w/ missing/blank `reason` | 400 `REVIEW_VALIDATION_FAILED`; no row, no transition, no audit; state stays READY_FOR_REVIEW; `humanReviewRequired` still `true` | bean validation + service guard — the defined T12 scenario |

Supplementary suites executed alongside the matrix (details in
`phase-8-verification.md`): S1 case isolation, S2 human-review bypass
attempts, S3 failure/resiliency, S4 security (XSS/IDOR/injection/
disclosure/CSRF), S5 AI-agent safety, S6 UI regression.
