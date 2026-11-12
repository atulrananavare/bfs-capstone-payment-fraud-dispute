# Phase 7 Verification — Analyst Workbench UI

Verification of the six-screen Thymeleaf analyst workbench against the
textual specification (screenshots / participant guide were unavailable;
the written requirements were treated as authoritative). Verified
2026-11-12 against a live instance and the full automated suite.

## Environment

- Java: OpenJDK 21.0.12.1 · Maven 3.8.7 · Spring Boot 3.5.16 ·
  Spring AI 1.1.8 · Thymeleaf (Spring Boot starter)
- DB: H2 in-memory (`MODE=LEGACY`), Flyway V1–V4, `ddl-auto=validate`
- Processor under test: **RULES** (default; no AI provider, credentials
  or vector store required). SPRING AI rendering verified via
  `UiSpringAiTest` with a mocked model.
- Reviewer identity: `TRAINING-REVIEWER-001` / role `REVIEWER` (config
  identity seam).
- Live walkthrough: jar `fraud-case-agent-0.1.0-SNAPSHOT` on `:8081`
  (a stale pre-Phase-7 process held `:8080`), cases `FC-9387041A` and
  `FC-3D2832DD`.

## Architecture compliance

- UI controllers call application services only (`FraudCaseService`,
  `InvestigationService`, `ReviewService`, `CaseMessageService`,
  `ReviewerIdentityProvider`); no repository writes, no business-logic
  duplication, no direct state mutation. `FraudCase.transitionTo` stays
  the sole state path.
- Spring Security supplies CSRF on `/ui/**` browser POSTs only;
  `/api/**` is CSRF-exempt and REST behaviour is unchanged (all Phase
  1–6 API tests still green).
- `SecurityConfig` (`config/SecurityConfig.java`) permits all requests at
  the HTTP layer; authorisation remains in the application layer
  (`ReviewService` rejects non-REVIEWER actors — UI hiding is never
  treated as security).
- No React/Angular/Vue/SPA; ordinary HTML/CSS + ~60 lines of progressive
  JavaScript (`static/js/app.js`: double-submit guard, conditional
  target-route enablement).

## Screen-by-screen evidence

### 1. Case Intake — `GET /ui/cases/new`, `POST /ui/cases`

- All required fields rendered: report channel, **masked** customer
  reference (labelled "masked", pattern `CUST-••••-NNNN`), home region,
  synthetic transaction reference, amount, ISO currency, occurrence
  time, transaction status/channel, **masked** merchant, issue type,
  customer statement, card possession, merchant recognition, plus the
  mandatory *synthetic training data* and *mandatory human review*
  confirmations.
- Visible synthetic-data notice in header and on the form.
- Server-side validation renders inline field errors: verified live —
  blank statement + amount `-5` → field errors, no case created;
  `<script>` in statement → rejected; bogus enum values → safe generic
  "Please provide a valid value for this field." (no framework
  internals — `messages.properties` `typeMismatch` override added after
  a live finding); `maskedCustomerRef=CUST-1` → masked-pattern error.
- POST created `FC-9387041A`, `FC-3D2832DD` → `302` redirect to
  `…/investigation`.

### 2. Investigation — `GET /ui/cases/{id}/investigation`, `POST …/investigate`

- Bounded plan lists the six allow-listed tools; actual executions shown
  only from persisted `ToolTrace` rows. Live: 6× `SUCCESS` with
  durations (0–4 ms) and trace ids (`TRC-B699F29488B54D38`,
  `TRC-41C264E38FA94D92`, `TRC-B2C799FA0C0F43EE`,
  `TRC-6F0EDDCAA33E4155`, `TRC-DF0C5FB36B0E4DE3`,
  `TRC-2115E56A7FF14A35`), timestamps, redacted result summaries.
- `FAILED`/`TIMED_OUT`/`UNAVAILABLE` styled distinctly (redacted summary
  column shows bounded key=value output, never payloads).
- Missing information shown verbatim: "transaction authentication
  outcome unavailable". No chain-of-thought anywhere on screen.
- Retry control offered only in `ENRICHING`/`INCOMPLETE` states.

### 3. Recommendation — `GET /ui/cases/{id}/recommendation`

- Live (rules): Classification `UNAUTHORISED_TRANSACTION`, Risk `HIGH`,
  Route `FRAUD_INVESTIGATION`, Confidence `85% advisory only`, Source
  `RULES`, `Recommendation ID REC-32D118EFEC3B4A83`, rationale `R003…`,
  evidence count link, policy link, missing information list.
- Permanent "ADVISORY RECOMMENDATION — human review required" banner;
  "Human review REQUIRED" row; the record is read-only — no mutation
  control exists.
- Case-scoped follow-up composer present but secondary; in RULES mode
  the message "Conversation service unavailable in RULES mode — this
  screen remains fully usable without it." verified live.

### 4. Evidence — `GET /ui/cases/{id}/evidence`

- "Customer Reported" claim section separated from "Observed by Tool"
  evidence; the claim is annotated "self-reported — not independently
  verified".
- Live case shows all seven evidence types: CUSTOMER_CLAIM (provenance
  `CUSTOMER_INTAKE`), TRANSACTION, DEVICE, LOCATION, MERCHANT,
  PRIOR_DISPUTE, SLA — each with evidence id, provenance label,
  timestamp and investigation id. Empty sections state "No evidence
  recorded"; nothing is fabricated.

### 5. Policy Citations — `GET /ui/cases/{id}/policy`, `…/policy/citations/{cid}`

- Retrieval context always rendered: Top-K `4`, threshold `0.72`,
  retrieval status. Live rules-mode case shows status `NOT_REQUESTED`
  with the explicit abstention message — no invented citation.
- `UiSpringAiTest` (mocked model) verifies citation rows: document id,
  title, version, effective date, section, chunk id, score, exact
  passage and the **Open Passage** detail view at
  `…/policy/citations/{citationId}`.
- `UiPolicyAbstentionTest` verifies no-match/abstention rendering on the
  review screen ("gap acknowledged") without fabricated policy.
- Unknown citation id → safe HTML 404 (verified live).

### 6. Human Review — `GET /ui/cases/{id}/review`, `POST …/review`

- Advisory recommendation summary + "Human decision required / advisory"
  banner; trusted reviewer `TRAINING-REVIEWER-001` (`REVIEWER`) shown
  read-only — it is never a form field.
- All five dispositions rendered (ESCALATE, REQUEST_MORE_INFORMATION,
  REROUTE, OVERRIDE, CLOSE); target-route select enabled only for
  REROUTE/OVERRIDE; conditional evidence/policy/missing-info/
  contradiction acknowledgements; mandatory rationale (T12); audit
  preview (case id, recommendation id, reviewer, "recorded by server on
  submit"); immutable review history block.
- **T12 verified**: `OVERRIDE` + blank reason → re-rendered form with
  errors, `reviewRepository` empty (`UiControllerTest` + live).
- **Stale protection verified live**: POST with wrong/empty
  `recommendationId` → "The recommendation changed after this review
  was opened. Reload the latest review context before submitting."
- **Valid UI submission verified live**: `FC-3D2832DD` ESCALATE with
  acknowledgements → `302`, persisted `REV-57993D1442EB4EDD`, case
  `ROUTED`, visible in `GET /api/cases/{id}/reviews`.
- Second case `FC-9387041A` dispositioned via REST API
  (`REV-20B104546C7C42AC` → `ROUTED`) — same service, both paths equal.

## Security verification

- **CSRF**: POST `/ui/…` without token → `403` (live). Tokens rendered in
  every form. `/api/**` unaffected (POSTs without tokens still work;
  `FraudCaseControllerTest` updated to `@Import(SecurityConfig.class)`
  because `@WebMvcTest` slices don't scan plain `@Configuration` — it now
  exercises the real chain).
- **XSS**: zero `th:utext` in templates (scanned); `<script>` in
  statements is rejected at validation; reviewer/policy text escapes.
- **Authorisation**: `UiAnalystRoleTest` — ANALYST identity submit →
  application-layer denial; `UiControllerTest` covers auditor-visible
  history without submit rights.
- **No secrets**: no credentials in templates/config/tests/docs;
  `UserDetailsServiceAutoConfiguration` excluded so no generated
  password noise; `application.yml` H2 `password: ""` is an empty local
  dev placeholder.
- **No forbidden capabilities**: scan of `ui/`, templates and static
  assets — no refund/block-card/transfer/credit/customer-contact/filing
  code paths or controls.
- **No internals**: no prompts, chain-of-thought, stack traces or raw
  payloads rendered; UI 404/500 pages are safe generic templates.

## Loading / error / empty / unavailable / partial states

- Empty intake workspace, empty evidence/policy sections, "not ready for
  human review" state (`UiControllerTest.reviewNotReadyState`), safe
  404 (`UiExceptionAdvice` outranks the REST `GlobalExceptionHandler` for
  `/ui/**`), rules-mode conversation unavailability, tool failure
  statuses, retry affordance, disabled-submit JS guard, idempotent
  re-submit replay. All verified by tests and/or live walkthrough.

## Accessibility

`docs/ui-accessibility-checklist.md` applied: landmarks, `aria-current`,
labelled controls, fieldset/legend disposition group, `role="alert"`
error summary, `aria-invalid`/`aria-describedby`, visible `:focus-visible`
outline, AA-checked palette, responsive breakpoint (nav collapses ~360px),
state shown as text not colour-only.

## Regression results

- `mvn clean test`: **Tests run: 381, Failures: 0, Errors: 0, Skipped:
  0** (includes all Phase 1–6 suites + 30 new UI tests:
  `UiControllerTest` 25, `UiSpringAiTest` 3, `UiAnalystRoleTest` 1,
  `UiPolicyAbstentionTest` 1).
- `mvn clean verify`: jar `fraud-case-agent-0.1.0-SNAPSHOT.jar` builds
  (~83 MB).

## Defects found & fixed during verification

1. Invalid-intake test sent duplicate `.param()` values — test bug,
   fixed by single-value helper.
2. REST `GlobalExceptionHandler` shadowed UI error pages — fixed with
   `@Order(HIGHEST_PRECEDENCE)` on `UiExceptionAdvice`.
3. `@WebMvcTest` slice applied Boot's default security chain (403/401) —
   fixed by `@Import(SecurityConfig.class)` in `FraudCaseControllerTest`.
4. Binding type-mismatch leaked framework internals in field errors —
   fixed via `messages.properties` `typeMismatch` override.
5. `UserDetailsServiceAutoConfiguration` excluded — removed generated-
   password startup noise.

## Remaining gaps

- SPRING-AI-mode live citation rendering was verified via mocked-model
  tests (`UiSpringAiTest`), not a live provider (no credentials —
  by design).
- Pixel-perfect parity with the original participant screenshots is not
  applicable (screenshots unavailable); information architecture,
  controls, data and safety criteria are all satisfied.

PHASE 7 IMPLEMENTATION: PASS
