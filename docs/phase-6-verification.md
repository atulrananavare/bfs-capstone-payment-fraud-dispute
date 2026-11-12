# Phase 6 Verification — Mandatory Human Review Workflow

Date: 2026-11-12. Module: `com.example:fraud-case-agent:0.1.0-SNAPSHOT`.

Phase 6 adds the human-review disposition workflow: typed dispositions,
trusted reviewer identity, authorization boundary, mandatory rationale,
acknowledgement checklist, recommendation-anchored concurrency control,
idempotent retry, immutable persistence and append-only audit — with no
banking action at any point.

## Requirement matrix

| Requirement | Implementation Evidence | Test Evidence | Actual Result | PASS/FAIL |
|---|---|---|---|---|
| `POST /api/cases/{caseId}/reviews` | `api.ReviewController` + `application.ReviewService` | `ReviewApiTest.hr01` | 201 + persisted disposition + `resultingCaseStatus` | PASS |
| Review read/context endpoints | `GET .../reviews`, `GET .../review-context` | `hr01`, `reviewContextExposesEvidenceAndFlags` | case-scoped, deterministic | PASS |
| Typed request; legitimate fields only | `ReviewRequest` + `@JsonIgnoreProperties(ignoreUnknown=false)` | `hr10`, `fakeEvidenceAndCitationFieldsRejected` | unknown fields → 400 | PASS |
| Trusted reviewer identity | `ReviewerIdentityProvider`/`SyntheticReviewerIdentityProvider`, `capstone.review.actor-*` | `hr01` asserts `TRAINING-REVIEWER-001` | server-side identity persisted + audited | PASS |
| Reviewer spoofing | payload `reviewerId`/`nextState`/`reviewedAt` rejected | `hr10` | 400, no disposition | PASS |
| Authorization — REVIEWER role | `ReviewerPrincipal.mayDisposition` + `REVIEW_FORBIDDEN` | `ReviewAuthorizationTest.hr09` | 403 + `REVIEW_AUTHORIZATION_DENIED`; no row/state change | PASS |
| Authorization — AUDITOR read-only | role gate on POST; open GET history/context | `ReviewAuditorTest` | GET 200, POST 403 + denial audit | PASS |
| Typed dispositions | `ReviewDispositionType` enum | `hr01–hr05`, `unknownDispositionFailsSafely` | ESCALATE/RMI/REROUTE/OVERRIDE/CLOSE only | PASS |
| Unknown disposition (`AUTO_REFUND`) | enum binding failure | `unknownDispositionFailsSafely` | 400 `MALFORMED_REQUEST`, no row | PASS |
| Mandatory reason | `@NotBlank` + service guard | `hr02`, T12 | null/blank/whitespace → 400, nothing persisted | PASS |
| Reason length/markup | `@Size(2000)` + `@NoUnsafeMarkup`/`@NoSensitiveContent` | `reasonLengthAndMarkupAreBounded` | oversized & `<script>` → 400 | PASS |
| T12 override-without-reason | bean validation + service guard | `hr02` | REJECTED; no row; state READY_FOR_REVIEW; `humanReviewRequired` still true | PASS |
| Evidence acknowledgement | `validate()` requires `ackEvidence` when evidence exists | `hr01`, `hr16` | 400 without, 201 with | PASS |
| Policy acknowledgement | citations on rec → `ackPolicy` | `ReviewSpringAiTest.hr17` | 400 without, 201 with | PASS |
| No-policy case | no citations → `policyAcknowledgementRequired=false`; gap via missing-info ack | `hr18` | gap acknowledged; no fabricated citation | PASS |
| Missing-information ack | `missingInformation` non-empty → `ackMissing` | `hr16` | 400 without, 201 with; authentication stays UNAVAILABLE | PASS |
| Contradiction ack | `missingInformation` containing "contradict" → `ackContradictions` | `hr19` | surfaced in context; 400 without ack | PASS |
| ESCALATE | `ReviewService` target=ROUTED | `hr01` + live demo | ROUTED, disposition only | PASS |
| REQUEST_MORE_INFORMATION | `UNDER_REVIEW→INCOMPLETE` refinement | `hr04` | INCOMPLETE; missing info retained | PASS |
| REROUTE | `targetRoute` required, valid, ≠ recommended | `hr03`, `rerouteRequiresTargetRouteAndDifferentRoute` | human route persisted; rec untouched | PASS |
| Invalid REROUTE | targetRoute null/unknown/identical → reject | `hr03` variants | 400/409, no row, no transition | PASS |
| OVERRIDE | targetRoute + reason + acks | `hr02` path + validation | stored separately from recommendation | PASS |
| CLOSE | target=CLOSED | `hr05` | CLOSED; disposition record only | PASS |
| Recommendation immutability | rec entity never rewritten; review separate row | `hr01`, `hr03`, `reviewReasonIsRecordedAsDataOnly` | rec classification/route/mode/citations unchanged | PASS |
| `humanReviewRequired` stays true | constructor enforces; review path never mutates rec | `hr01` post-check | still `true` after review | PASS |
| Review immutability | `@PreUpdate`/`@PreRemove`; no mutation endpoints | `hr20` | delete → `InvalidDataAccessApiUsage`; row preserved | PASS |
| Review history ordering | `ORDER BY recorded_at, review_id` | `reviewHistoryIsDeterministicallyOrdered` | RMI then ESCALATE, stable order | PASS |
| State machine | `CaseState` + `ReviewService` transitions | `CaseStateTransitionTest`, `hr08` | READY→UNDER→ROUTED/CLOSED/INCOMPLETE | PASS |
| CLOSED terminal | state gate | `hr05`/`hr06` | further reviews → 409 `REVIEW_NOT_PERMITTED` | PASS |
| ROUTED second-review protection | only READY_FOR_REVIEW accepts submissions | `hr07` | 409 `REVIEW_NOT_PERMITTED` | PASS |
| INCOMPLETE review handling | state gate | `hr08` | final disposition → 409; documented semantics | PASS |
| Review without recommendation | latest rec lookup fails first | `reviewWithoutRecommendationIsRejected` | 404 `RECOMMENDATION_NOT_FOUND` | PASS |
| Stale recommendation | `recommendationId` must equal latest | `hr13` | 409 `STALE_RECOMMENDATION` | PASS |
| Duplicate submission | `clientReference` + unique `(case_id, client_reference)` | `hr14` | replay returns stored review; 1 row, 1 audit | PASS |
| Concurrent reviews | `@Version` on `fraud_case` + unique `recommendation_id` | `hr15` | exactly one 201, one 409, one disposition | PASS |
| Cross-case recommendation | id must equal case's own latest | `hr11` | 409 `STALE_RECOMMENDATION`, no leakage | PASS |
| Cross-case review read | case-scoped repository query | `hr12` | no data returned | PASS |
| AI cannot review | `ReviewService` unreachable from agent/tools/memory | `hr21` + source scan | no model-callable review tool exists | PASS |
| Conversation cannot review | `CaseMessageService` never touches `ReviewService` | `hr22` | message posted; no disposition, state unchanged | PASS |
| Customer prompt-injection cannot review | intake text is evidence only | `customerStatementInjectionCannotReview` | no disposition; review still required | PASS |
| Policy prompt-injection cannot review | adversarial docs rejected at ingestion; passages are data | `PolicyIngestionServiceTest` adversarial cases + `hr24`-style grounding tests | no disposition/state change | PASS |
| Review-reason injection | reason persisted verbatim as data | `reviewReasonIsRecordedAsDataOnly` | rec unchanged; no side effects | PASS |
| Reviewer cannot fabricate evidence | unknown fields rejected; context reads persisted evidence | `fakeEvidenceAndCitationFieldsRejected` | `EVI-FAKE` field → 400 | PASS |
| Reviewer cannot fabricate policy | unknown fields rejected; citations come from Phase 4 retrieval | `fakeEvidenceAndCitationFieldsRejected` | `POL-FAKE-999` field → 400 | PASS |
| Provider outage | review reads persisted data only | `hr23`, `rulesFallbackRecommendationIsReviewable` | review + context work with provider down | PASS |
| VectorStore outage | retriever mocked to throw | `ReviewOutageTest` | context + submit succeed | PASS |
| Memory outage | ChatMemory mocked to throw | `ReviewOutageTest` | review unaffected | PASS |
| Rules recommendation review | review path uses no AI beans | `ReviewApiTest` (rules context) + live demo | works, no ChatModel needed | PASS |
| Rules-fallback review | `RULES_FALLBACK` source visible in context | `rulesFallbackRecommendationIsReviewable` | reviewable normally | PASS |
| Insufficient-evidence review | INCOMPLETE state gate; `ackMissing` for gaps | `hr08`, `hr16` | controlled rejection / gap ack path | PASS |
| Standard CNP review | grounded `POL-CNP-017` v3.2 §3.2 citation | `standardCnpReviewEscalatesWithGroundedCitation` + live rules demo | ROUTED with acknowledgements | PASS |
| Merchant-dispute review | `POL-MRD-004` grounded | `merchantDisputeReviewEscalatesToDisputeOps` | ROUTED | PASS |
| Duplicate-processing review | `POL-DUP-009` grounded | `duplicateProcessingReviewEscalatesToPaymentOps` | ROUTED, no refund | PASS |
| Audit reconstruction | `REVIEW_*` + `CASE_STATE_CHANGED` chronological | `auditTrailReconstructsInvestigationAndReview` | CASE_CREATED→…→REVIEW_STARTED→REVIEW_SUBMITTED | PASS |
| Audit immutability | `AuditEventAppender` exposes no mutation | Phase 2 audit tests + `hr20`-style guards | append-only | PASS |
| Review API data exposure | masked refs only; no secrets/prompts/raw vectors | response-shape assertions in `hr01`, `reviewContextExposesEvidenceAndFlags` | clean | PASS |
| No autonomous banking action | no block-card/refund/credit/transfer/filing/contact code | `hr59` forbidden-capability scan + source scan | none exist | PASS |
| Phase 1 regression | intake/masking/health/config tests | full suite | green | PASS |
| Phase 2 regression | persistence/tools/audit/rules tests | full suite | green | PASS |
| Phase 3 regression | spring-ai output/tools/fallback tests | full suite | green | PASS |
| Phase 4 regression | RAG/citation/abstention tests | full suite | green | PASS |
| Phase 5 regression | memory/message/isolation tests | full suite | green | PASS |
| Rules-mode review | `capstone.processor-mode=rules`, no credentials | `ReviewApiTest` + live demo | works offline | PASS |
| Security scan | forbidden-tool/secret/identity scans | see below | clean | PASS |

## Totals

`mvn clean verify`: **351 tests, 0 failures, 0 errors, 0 skipped**
(343 prior + 8 new strict-verification tests). Jar built.

## Live demonstration (rules mode, no AI/vector store)

- Case `FC-934A0D16`, investigation `REC-BABB5CEF837A4A84`:
  `UNAUTHORISED_TRANSACTION`, `HIGH`, `FRAUD_INVESTIGATION`,
  `humanReviewRequired=true`, missing: `transaction authentication outcome
  unavailable`.
- `GET /review-context` → state READY_FOR_REVIEW, evidence summaries,
  missing information visible, `policyCitations: []`.
- `POST /reviews` ESCALATE (reason + all acknowledgements) → 201
  `REV-C1484DE6675447E8`, `reviewerId=TRAINING-REVIEWER-001`,
  `resultingCaseStatus=ROUTED`.
- `POST /reviews` OVERRIDE with blank reason → 400.
- `GET /reviews` → exactly one immutable disposition row.

## Safety notes

- No block-card/refund/credit/transfer/filing/customer-contact code exists;
  review dispositions record workflow state only.
- Rejection audits (`REVIEW_REJECTED`, `REVIEW_AUTHORIZATION_DENIED`)
  commit via `noRollbackFor`; conflict paths write nothing.
- `reviewerId`, timestamps, and state are server-side only — the request
  schema rejects unknown fields.
- Adversarial policy documents are rejected at ingestion; retrieved
  passages are treated as untrusted data and can never trigger a review.

Phase 7 not started.
