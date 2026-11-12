# Phase 4 Verification — Policy Grounding & RAG

Date: 2026-11-12. Branch: `main`. Module: `com.example:fraud-case-agent:0.1.0-SNAPSHOT`.
Verifier: strict Phase-4 verification pass (implementation + executable test evidence
required for every PASS row).

## Environment

| Item | Actual |
|---|---|
| Java | OpenJDK 21.0.12.1 |
| Maven | 3.8.7 |
| Spring Boot | 3.5.16 (parent) |
| Spring AI | 1.1.8 — `spring-ai-bom` import; `spring-ai-client-chat`, `spring-ai-anthropic`, `spring-ai-vector-store` (+ transitive `spring-ai-model/commons/template-st/retry`) all resolve to 1.1.8 |
| Embedding impl. | `rag.DeterministicEmbeddingModel` (offline, `EmbeddingModel` impl, 256-dim hashed canonical tokens) — default; replaceable via `EmbeddingModel` bean |
| VectorStore impl. | `rag.SyntheticPolicyVectorStore` implements `org.springframework.ai.vectorstore.VectorStore` — in-memory, deterministic; replaceable via `@ConditionalOnMissingBean` |
| Database | Embedded H2 2.3.232 (in-memory) |
| Migration tool | Flyway 11.7.2 (`V1`, `V2` applied; Hibernate `ddl-auto=validate`) |
| LangChain4j | NOT present — zero `dev.langchain4j`/`langchain4j` references or dependencies |
| Credentials | `spring.ai.anthropic.api-key` via env var only; no secrets in repo; rules mode needs none |

## Build results (this verification run)

- `mvn clean test` → **Tests run: 250, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**
- `mvn clean package` → BUILD SUCCESS (`fraud-case-agent-0.1.0-SNAPSHOT.jar`)
- Offline: entire suite runs with no provider credential, no embedding API, no
  external vector store, no network-dependent step. Any future live-provider
  RAG check must sit behind an explicit opt-in profile (none exists today).

## Policy pack inventory (`src/main/resources/policies/`)

| documentId | title | version | effectiveDate | owner | status | sections |
|---|---|---|---|---|---|---|
| POL-CNP-017 | Card-Not-Present Dispute Handling Procedure | 3.1 | 2026-05-01 | Payment Fraud Operations | SUPERSEDED (by 3.2) | 5 |
| POL-CNP-017 | Card-Not-Present Dispute Handling Procedure | **3.2** | 2026-10-01 | Payment Fraud Operations | **CURRENT** | 7 (incl. `## 3.2 Initial fraud routing`) |
| POL-CNP-099 | Future Card Fraud Draft Procedure | 9.9 | 2027-06-01 (future) | Payment Fraud Operations | CURRENT | future-dated control |
| POL-DUP-009 | Duplicate Transaction Handling Procedure | 2.0 | 2026-07-01 | Payment Operations | CURRENT | 4 |
| POL-ESC-021 | Suspicious Transaction Escalation Procedure | 1.1 | 2026-09-01 | Payment Fraud Operations | CURRENT | ≥2 |
| POL-EVD-002 | Evidence Sufficiency and Manual Review Standard | 2.3 | 2026-09-15 | Fraud Review Standards | CURRENT | ≥2 |
| POL-HRV-001 | Human Review Requirements Standard | 1.0 | 2026-06-01 | Fraud Review Standards | CURRENT | ≥2 |
| POL-LEG-050 | Withdrawn Legacy Fraud Circular | 1.0 | 2024-01-01 | Payment Fraud Operations | WITHDRAWN | ≥2 |
| POL-MRD-004 | Merchant Dispute Handling Procedure | 1.4 | 2026-08-15 | Dispute Operations | CURRENT | 5 |

All 9 documents carry `classification: SYNTHETIC_TRAINING`. Required coverage:
A CNP fraud (POL-CNP-017), B merchant disputes (POL-MRD-004), C duplicate
processing (POL-DUP-009), D suspicious escalation (POL-ESC-021), E evidence
sufficiency/manual review (POL-EVD-002), F human review (POL-HRV-001).
Plus adversarial test doc `src/test/resources/policies-adversarial/POL-ADV-001.md`.

Reference CNP policy POL-CNP-017 v3.2 §3.2 contains all required concepts:
customer denies CNP transaction; independently sourced device/location
anomalies → initial fraud-investigation routing; unavailable authentication
recorded as missing evidence (no assumed success/failure); analyst review
remains required.

## Verification matrix

| Requirement | Implementation Evidence | Test Evidence | Actual Result | PASS/FAIL |
|---|---|---|---|---|
| Synthetic policy pack | 9 docs under `src/main/resources/policies/`, all `SYNTHETIC_TRAINING` | `PolicyIngestionServiceTest.rag01` (9 files, 35 chunks, 0 rejections) | Pack ingests cleanly; no real bank content | PASS |
| Required policy metadata | `PolicyMetadata.violations()` requires documentId, title, version, effectiveDate, owner, classification, status; `SYNTHETIC_TRAINING` enforced | `rag02_missingOrInvalidMetadataIsRejectedAndNothingIndexed` | Missing version / wrong classification rejected, store stays empty | PASS |
| Metadata validation (missing documentId/version/effectiveDate/section) | Front-matter validation + parser rejects blank `## ` heading (fixed this pass) | `rag02_*`, new `rag02_blankSectionHeadingIsRejected` | Ingestion rejected safely, nothing indexed, no crash | PASS |
| Meaningful chunking | `PolicyChunker`: one chunk per `## ` section; paragraph split only over 1200 chars; heading preserved verbatim in text | `rag01` asserts `POL-CNP-017-3.2-S04-C01` = §3.2; probe dump shows CNP §3.2, MRD §2, DUP §3 chunks intact | Sections never cut mid-rule by char count alone | PASS |
| Stable chunk IDs | `<documentId>-<version>-S<nn>-C<mm>` derived from content structure | `rag03_chunkIdsAreStableAcrossRepeatedIngestion`, `rag14` idempotency | Identical ingest → identical ids; version change → different id | PASS |
| Embedding abstraction | `DeterministicEmbeddingModel implements EmbeddingModel`; domain has zero `org.springframework.ai` imports | `DeterministicEmbeddingModelTest` (5): determinism, unit-norm, synonym overlap, request/response | Offline deterministic embeddings; provider details stay in config | PASS |
| VectorStore integration | `SyntheticPolicyVectorStore implements VectorStore`; metadata stored per doc; catalog is trusted registry | `rag01`, `rag13_passageTextIsPreservedVerbatim` | Metadata (documentId/title/version/date/owner/class/section/chunkId) survives round-trip | PASS |
| Offline embedding/vector tests | Defaults are in-memory/deterministic; `@ConditionalOnMissingBean` allows replacement | Whole suite runs with zero credentials/network | `mvn clean test` green offline | PASS |
| Top-K (default 4, config-driven) | `capstone.retrieval.top-k` → `RagConfig` → `PolicyRetriever.topK`; no literal `4` in business logic | `CapstonePropertiesTest.retrievalAndToolDefaults…` (default=4); `rag08_topKBoundsTheResultSet` (topK=1 honored) | Config-driven; bounded; not a license for irrelevant results | PASS |
| Similarity threshold (default 0.72, inclusive `>=`) | `capstone.retrieval.similarity-threshold` → retriever drops `score < threshold` in application code | new `thresholdIsInclusiveAtTheConfiguredBoundary`: 0.72 kept, 0.719 → NO_MATCH | Inclusive boundary documented + tested | PASS |
| Invalid retrieval config rejected | `@Min(1) topK`, `@DecimalMin(0.0)/@DecimalMax(1.0)` on `CapstoneProperties.Retrieval` (`@Validated` at startup) | new `invalidRetrievalConfigurationFailsValidation`: topK=0/-2, threshold=-0.1/1.5 all violate | Safe startup validation failure | PASS |
| Exact-query retrieval | `PolicyRetriever.retrievePolicyEvidence` + `PolicyQueryBuilder` | `rag04_standardCnpQueryFindsTheCnpRoutingSection` | POL-CNP-017 v3.2 §3.2, chunk `…-S04-C01`, score **1.0000** ≥0.72 | PASS |
| Paraphrase retrieval | `CanonicalTokenizer` synonym/phrase canonicalisation ("seller"→merchant, "unfamiliar"→unrecognised) | `rag05_paraphrasedMerchantQueryFindsMerchantPolicy`, `synonymCanonicalisationCreatesOverlap` | Paraphrased query retrieves POL-MRD-004 — semantic not string match | PASS |
| Irrelevant-query abstention | `PolicyRetrievalOutcome.Status.NO_MATCH` typed state | `rag06_irrelevantQueryAbstainsWithNoMatch` ("office cafeteria lunch menu"), `SpringAiRagNoMatchTest` | NO_MATCH; no citation, no fabricated passage, MANUAL_REVIEW, humanReviewRequired=true | PASS |
| Version filtering | `PolicyCatalog.effectiveVersion` (max CURRENT non-future version); retriever drops non-effective | `rag09_supersededVersionNeverReturnedEvenWhenItMatches` | v3.1 scores but is filtered; v3.2 cited | PASS |
| Effective-date filtering | `m.effectiveDate().isAfter(asOf)` → drop; catalog `effectiveVersion` also gates | `rag10_futureEffectivePolicyIsFiltered` | POL-CNP-099 (2027) never returned | PASS |
| Stale/withdrawn policy | `Status.WITHDRAWN`/`SUPERSEDED` never effective; only-stale corpus → NO_MATCH | `rag11_withdrawnPolicyIsFiltered`, `rag09` (old-only corpus → NO_MATCH) | Stale policy cannot become authoritative | PASS |
| `retrievePolicyEvidence` implementation | `PolicyRetriever.retrievePolicyEvidence` — over-fetch → threshold → catalog-effective filter → top-K; RuntimeException → UNAVAILABLE | `PolicyRetrieverTest` (13), `rag29_vectorStoreFailureYieldsUnavailableNotException` | Typed outcome; failure contained | PASS |
| PolicyRetriever output fields | `PolicyPassage(documentId,title,version,effectiveDate,owner,section,chunkId,score,passage)` | `rag12_citationMapsTrustedRetrievalMetadataVerbatim` | All required fields present; classification SYNTHETIC_TRAINING | PASS |
| Exact PolicyCitation mapping | `PolicyCitation.from(PolicyPassage)` — only construction path; model supplies chunkId only | `modelCitedChunkIdResolvesToTrustedCitation`, `rag12` | Citation fields come from retrieval, never model text | PASS |
| Exact passage traceability | `PolicyChunk.text` preserved verbatim through store → passage → citation → `policy_citation.passage` column (V2) | `rag13_passageEqualsIngestedChunkExactly`, e2e citation API asserts verbatim passage | citation → exact source chunk deterministic | PASS |
| SpringAiFraudCaseAgent grounding | Passages injected in `<untrusted_policy_passages>` + `<valid_policy_passage_ids>` in user msg; system prompt rule 8; citations resolved only from this case's passages | `passagesAppearAsUntrustedDataWithValidIdList`, `SpringAiRagInvestigationTest` | Grounded recommendations persist with real citations | PASS |
| Model general-knowledge restriction | `resolveCitations` throws `InvalidModelOutputException` on any chunkId not retrieved this case | `fabricatedChunkIdIsRejectedAsInvalidOutput`, `fabricatedCitationRejectedAndFallsBackDeterministically` (POL-FAKE-999 → RULES_FALLBACK, POLICY_CITATION_REJECTED) | Invented policy can never persist | PASS |
| RecommendationValidator citation checks | allowedCitations = citations of this case's passages; doc/version/section/chunkId equality + score ±0.001 + passage equality; NO_MATCH/UNAVAILABLE → MANUAL_REVIEW or INSUFFICIENT_EVIDENCE | `PolicyCitationValidationTest` (12): fake doc, wrong version, wrong section, wrong chunk, score mismatch, tampered passage, foreign case, never-retrieved | Every fake/foreign/tampered citation rejected | PASS |
| Procedural-claim grounding | SPRING_AI rationale containing "policy"/"procedure" with zero citations while passages exist → violation | `rag22_proceduralClaimWithoutCitationIsRejected` | Ungrounded policy claim rejected | PASS |
| R005 no-valid-policy deterministic rule | `RulesFraudCaseAgent.applyR005`: NO_MATCH/UNAVAILABLE → MANUAL_REVIEW, confidence ≤0.5, missingInformation + ruleIds+R005 | `noMatchOverCommittingModelIsRejectedAndR005RoutesToManualReview`, `retrievalFailureOverCommittingModelFallsBackToR005` | R005 fires; no fabricated citation; humanReviewRequired=true | PASS |
| Standard CNP case (CUST-••••-4821 / TXN-2026-784102) | Deterministic tools: authOutcome=UNAVAILABLE, deviceKnown=false/NEW_DEVICE, anomalyLevel=HIGH | `sampleCaseAuthOutcomeIsUnavailableNotInvented`, `deviceSignalsSampleCaseIsNewDevice`, `locationsSampleCaseIsHighAnomaly`, `standardCnpGroundedRecommendationPersistsWithCitation` | Retrieval: POL-CNP-017 v3.2 §3.2 score 1.0; citation persisted verbatim; auth never invented | PASS |
| Merchant-dispute scenario | POL-MRD-004 v1.4 grounded; R004 deterministic equivalent | `merchantDisputeGroundedOnMerchantPolicy`, `rag05` | MERCHANT_DISPUTE → DISPUTE_OPERATIONS, cites merchant policy not CNP | PASS |
| Duplicate-processing scenario | POL-DUP-009 grounded; R002 deterministic equivalent | `duplicateProcessingGroundedOnDuplicatePolicy` | DUPLICATE_PROCESSING → PAYMENT_OPERATIONS, exact citation | PASS |
| No-match scenario | NO_MATCH → no citation → R005/manual review | `SpringAiRagNoMatchTest` (3) | Explicit abstention; missingInformation carries policy gap | PASS |
| Policy prompt injection | POL-ADV-001 hostile text sits inside `<untrusted_policy_passages>`; system prompt rule 8; ACTION_CLAIM regex + humanReview enforcement reject obedient output | `adversarialPassageStaysInsideUntrustedTags`, `adversarialPolicyFileIngestsAsPlainData`, `SpringAiPromptInjectionTest` (12: refund approval, card block, no-review, fake citation, foreign evidence, prompt leak — all rejected) | Injection contained; policy text is data; humanReviewRequired stays true | PASS |
| Cross-case citation isolation | Citations resolve only against current-case passages; citations API scoped by case → recommendation | `foreignCaseChunkIdIsRejected`, `rag26_citationFromAnotherCasesRetrievalIsRejected`, `citationsEndpointIsCaseScopedNoForeignAccess` | Case A citation cannot attach to/open in case B | PASS |
| Arbitrary passage access | `/citations` has no path/doc parameter; only persisted citations of the case's latest recommendation | `citationsEndpointIsCaseScopedNoForeignAccess` (`?path=/etc/passwd` ignored) | Not a generic file/doc API | PASS |
| Embedding failure | Retriever catches RuntimeException → `UNAVAILABLE`; ingest failure propagates as explicit exception | `rag29_vectorStoreFailureYieldsUnavailableNotException`, `rag29_indexingFailureIsExplicit`, `retrievalFailureOverCommittingModelFallsBackToR005` | Safe UNAVAILABLE → R005 manual review; no invented policy | PASS |
| VectorStore failure | Same UNAVAILABLE path (store stub throws) | `rag29_vectorStoreFailureYieldsUnavailableNotException`, `SpringAiRagNoMatchTest` (mocked UNAVAILABLE) | Controlled failure, no leakage | PASS |
| Retrieval timeout | Any store RuntimeException (incl. timeout) → UNAVAILABLE → abstention path; no wall-clock wrapper needed for in-memory store | Same UNAVAILABLE coverage | Bounded by typed outcome; no partial authoritative result | PASS |
| Malformed policy ingestion | Parse/metadata violations → per-file rejection, batch continues, nothing partial indexed | `rag02_malformedDocumentIsRejectedNotFatal`, `rag02_missingOrInvalidMetadata…`, `rag02_blankSectionHeadingIsRejected` | Rejected safely; subsequent retrieval operational | PASS |
| Duplicate ingestion | Catalog `contains(chunkId)` skip + store overwrite by id | `rag14_reingestionIsIdempotentNoDuplicates` | Second ingest: 0 indexed, 35 skipped; no ranking distortion | PASS |
| Citation API | `GET /api/cases/{caseId}/citations` → documentId,title,version,effectiveDate,section,chunkId,score,passage | `SpringAiRagInvestigationTest` asserts fields + verbatim passage | No embeddings/credentials/foreign-case data | PASS |
| Retrieval observability | `POLICY_RETRIEVAL queryHash=… candidates=… kept=…`; audit `POLICY_RETRIEVAL_*` carries investigationId/query hash/status; ToolTrace duration only for tools | Log lines observed in test output; `auditTypes` assertions | Safe fields only: query SHA-256 hash, counts, status, ids | PASS |
| RAG audit events | STARTED, COMPLETED, NO_MATCH, FAILED, CITATION_ATTACHED, CITATION_REJECTED — append-only via AuditService | `SpringAiRagInvestigationTest`/`NoMatchTest` audit assertions | Case-scoped, timestamped, bounded, no vectors | PASS |
| Rules-mode regression | `processor-mode=rules` never touches retriever (`mode==SPRING_AI` gate); `retrieval.enabled=false` removes all RAG beans | `RetrievalDisabledRulesModeTest`, `RulesFraudCaseAgentTest` (13), `InvestigationApiTest` (9) | RULES works with zero RAG infrastructure | PASS |
| Spring-AI regression (Phase 3 suite) | All Phase-3 controls intact: structured output, caseId match, humanReview, enum parsing, foreign evidence/trace rejection, tool allow-list + budget, fallback | `SpringAiFraudCaseAgentTest` (17), `SpringAiModeInvestigationTest` (20), `SpringAiPromptInjectionTest` (12), `SpringAiToolAdapterTest` (7), `SpringAiPromptBuilderTest` (4) | Phase-4 did not weaken Phase-3 controls | PASS |
| Phase 1 regression | Intake validation, masking, ISO currency, unsafe-markup rejection, consent flags, health endpoint | `FraudCaseIntakeRequestValidationTest` (28), `FraudCaseControllerTest` (6) | Green | PASS |
| Phase 2 regression | JPA, Flyway V1+V2, deterministic tools, ToolTrace, provenance, audit, state transitions, rules engine | `PersistenceIntegrationTest` (11), `DeterministicToolsTest` (26), `CaseStateTransitionTest` (11) | Green | PASS |
| Sensitive-data/security scan | grep: no keys/secrets/real PANs; only `ANTHROPIC_API_KEY` env placeholders + `sk-...` doc example; no block/refund/credit/transfer/contact/filing code paths (validator ACTION_CLAIM only rejects such text) | Manual scan of src/resources/tests/logs | No secrets, no real customer/bank data, no autonomous actions | PASS |
| No embedding/vector leakage | No `float[]`/embedding/vector in API records, entities, audit details or logs; vectors live only inside store entries | Manual inspection of `InvestigationResponses`, entities, audit writes | Zero vector exposure | PASS |
| humanReviewRequired always true | `@AssertTrue` startup invariant; domain record default `true`; validator rejects false | Asserted in every e2e investigation test | Always true on every path | PASS |

## Chunking examples (live probe output)

| Policy | Source section | Resulting chunk | Content summary | Metadata preserved |
|---|---|---|---|---|
| POL-CNP-017 v3.2 | `## 3.2 Initial fraud routing` | `POL-CNP-017-3.2-S04-C01` | CNP denial + independent device/location anomalies → fraud investigation; auth gap = missing evidence; analyst review required | documentId, title, version 3.2, effectiveDate 2026-10-01, owner, SYNTHETIC_TRAINING, section, chunkIndex |
| POL-MRD-004 v1.4 | `## 2 Recognition test` | `POL-MRD-004-1.4-S02-C01` | Merchant-recognition criteria for dispute vs fraud | full front-matter + section + index |
| POL-DUP-009 v2.0 | `## 3 Payment-operations routing` | `POL-DUP-009-2.0-S03-C01` | Confirmed duplicate fingerprint → payment operations | full front-matter + section + index |

## Standard CNP case — retrieval evidence (live probe)

- Query (from `PolicyQueryBuilder`): `card not present transaction; customer denies unauthorised transaction; merchant not recognised; authentication outcome unavailable; new unknown device; untrusted device; high location anomaly; determine correct triage routing and required evidence`
- Top-K=4, threshold=0.72 → `RETRIEVED`, 16 candidates evaluated, 4 kept:

| documentId | version | section | chunkId | score |
|---|---|---|---|---|
| POL-CNP-017 | 3.2 | 3.2 Initial fraud routing | POL-CNP-017-3.2-S04-C01 | 1.0000 |
| POL-ESC-021 | 1.1 | 2 Anomaly sources | POL-ESC-021-1.1-S02-C01 | 1.0000 |
| POL-CNP-017 | 3.2 | 2 Evidence expectations | POL-CNP-017-3.2-S02-C01 | 0.7500 |
| POL-MRD-004 | 1.4 | 2 Recognition test | POL-MRD-004-1.4-S02-C01 | 0.7500 |

Top passage text is the verbatim §3.2 chunk (shown verbatim in
`standardCnpGroundedRecommendationPersistsWithCitation` persisted citation).
Authentication outcome for TXN-2026-784102 is `UNAVAILABLE` — never invented.

## Defects found & fixed this pass

1. **Missing executable evidence for invalid retrieval config** — added
   `CapstonePropertiesTest.invalidRetrievalConfigurationFailsValidation`
   (topK 0/-2, threshold -0.1/1.5 → Bean-Validation violations).
2. **Blank `## ` section headings ingested as section-less chunks** —
   `PolicyDocumentParser` now rejects them as malformed;
   `rag02_blankSectionHeadingIsRejected` added.
3. **Threshold boundary semantics untested** — added
   `PolicyRetrieverTest.thresholdIsInclusiveAtTheConfiguredBoundary`
   (score==0.72 kept, 0.719 dropped → documents inclusive `>=` semantics).
4. **Adversarial policy file not exercised through ingestion** — added
   `PolicyIngestionServiceTest.adversarialPolicyFileIngestsAsPlainData`
   (POL-ADV-001 indexes verbatim as data; containment verified downstream).

Focused tests re-run: 9+13+3 pass. Full suite re-run: **250/250 PASS**.

## Known limits

- The default embedding model is a vocabulary-hashed canonical-token scorer
  plus concept-coverage similarity — deterministic and offline, but not a
  neural semantic model. It is deliberately the default seam; a real
  `EmbeddingModel`/`VectorStore` bean would take precedence if ever supplied.
- Retrieval timeout handling is the generic UNAVAILABLE path (no dedicated
  wall-clock bound exists on the in-memory store; a real store would bound
  via its own client timeout).
- Phase 5 (conversational memory, review workflow, UI, message endpoints)
  not started — intentionally.
