# Analyst Workbench UI Guide (Phase 7)

The analyst workbench is a server-rendered Thymeleaf UI layered over the
verified Phase 1–6 application services. It is an **investigation and
recommendation assistant** — every recommendation is advisory and requires
human review. The UI can never execute banking, customer-contact, refund,
card-blocking, transfer, credit or regulatory-filing actions.

## Technology

- Spring Boot 3.5 + Thymeleaf, ordinary HTML/CSS, minimal JavaScript
  (`static/js/app.js`: submit-button double-submit guard and conditional
  target-route enablement only).
- Spring Security is used for **CSRF protection only** on browser form
  POSTs. The JSON API under `/api/**` is CSRF-exempt and unchanged.
- Reviewer identity comes from the configured trusted identity seam
  (`capstone.review.reviewer-id`, default `TRAINING-REVIEWER-001`) — never
  from form fields. UI hiding is not security; `ReviewService` enforces the
  boundary.

## Layout

Every screen shares three regions (rendered by `templates/fragments/`):

1. **Product/case header** — product name, active case id, case state
   badge, processor mode badge (RULES / SPRING AI), and a persistent
   *"Synthetic training data"* notice.
2. **Left case workflow navigation** — Case → Investigation →
   Recommendation → Evidence → Policy → Human Review, with the active
   step highlighted (`aria-current`).
3. **Main workspace** — the screen content.

A case-scoped follow-up thread is available on the recommendation screen
as *supporting* functionality only; it is never the primary experience.

## Routes

| Screen | URL | Template |
|---|---|---|
| Case list / start | `GET /ui/cases/new` | `cases/intake.html` |
| Create case | `POST /ui/cases` | → redirect to investigation |
| Case summary | `GET /ui/cases/{caseId}` | `cases/intake.html` (read-only summary) |
| Investigation | `GET /ui/cases/{caseId}/investigation` | `cases/investigation.html` |
| Run investigation | `POST /ui/cases/{caseId}/investigate` | → redirect |
| Recommendation | `GET /ui/cases/{caseId}/recommendation` | `cases/recommendation.html` |
| Follow-up message | `POST /ui/cases/{caseId}/messages` | → redirect |
| Evidence | `GET /ui/cases/{caseId}/evidence` | `cases/evidence.html` |
| Policy citations | `GET /ui/cases/{caseId}/policy` | `cases/policy.html` |
| Open Passage | `GET /ui/cases/{caseId}/policy/citations/{citationId}` | `cases/passage.html` |
| Human review | `GET /ui/cases/{caseId}/review` | `cases/review.html` |
| Submit review | `POST /ui/cases/{caseId}/review` | → redirect |
| UI errors | — | `error/404.html`, `error/error.html` |

All CSRF-protected form POSTs carry the `_csrf` hidden field rendered by
Thymeleaf.

## Screen 1 — Case Intake (`/ui/cases/new`)

Fields (all server-side validated; errors render inline beside the field):

- report channel, masked customer reference (`CUST-••••-NNNN`), home region
- synthetic transaction reference, amount, ISO currency, occurrence time,
  transaction status, transaction channel
- masked merchant name, issue type, customer statement
- card possession, merchant recognition
- required checkboxes: *synthetic training data confirmed* and
  *mandatory human review acknowledged*

Masked identifiers are visibly labelled "masked". Unsafe markup, invalid
identifiers, non-positive amounts, invalid dates, missing confirmations and
unknown fields are rejected with structured field errors. **Start
Investigation** creates the case via `FraudCaseService` and navigates to
the Investigation screen.

## Screen 2 — Investigation (`/ui/cases/{caseId}/investigation`)

- Bounded investigation plan: the fixed allow-list of six deterministic
  synthetic evidence tools.
- Actual tool executions only — persisted `ToolTrace` rows showing tool
  name, status, duration and trace id. FAILED / TIMED_OUT / UNAVAILABLE
  render distinctly.
- Missing-information list from the validated recommendation.
- Start / retry control when the case state permits. No chain-of-thought,
  prompts or model internals are ever rendered.

## Screen 3 — Recommendation (`/ui/cases/{caseId}/recommendation`)

- Classification, risk level, route, confidence, processor source/mode.
- Concise rationale (never chain-of-thought), evidence links, policy
  links, missing information.
- Prominent *"Advisory — human review required"* label; the original
  recommendation is read-only and no UI control mutates it.
- Case-scoped follow-up composer (bounded memory window).

## Screen 4 — Evidence (`/ui/cases/{caseId}/evidence`)

- Customer claim (intake) separated from tool-generated evidence.
- Device, location, transaction, merchant, prior-dispute and SLA sections
  rendered only when a persisted evidence item exists.
- Every item shows provenance (intake vs. tool name) and timestamp.
- Missing evidence is shown as an explicit empty state — never fabricated.

## Screen 5 — Policy Citations (`/ui/cases/{caseId}/policy`)

- Retrieval context: configured top-K and similarity threshold.
- Per citation: document id, title, version, effective date, section,
  chunk id, similarity score and exact passage.
- **Open Passage** links to a dedicated read-only passage view.
- Explicit abstention states: *no match* and *unavailable* render as an
  acknowledged gap — no policy text is ever invented.

## Screen 6 — Human Review (`/ui/cases/{caseId}/review`)

- Agent recommendation summary with advisory label.
- Trusted reviewer identity shown read-only (from configuration).
- All five dispositions: ESCALATE, REQUEST_MORE_INFORMATION, REROUTE,
  OVERRIDE, CLOSE.
- Evidence, policy and missing-information/contradiction acknowledgements
  rendered only when applicable; the review request validator enforces
  them.
- Reviewer rationale is mandatory (including for OVERRIDE — T12).
- Target-route selection is enabled only for REROUTE.
- Audit preview lists what will be recorded; existing immutable review
  dispositions are shown for traceability.
- Submit posts to the application `ReviewService` via the UI controller —
  state, stale-recommendation, duplicate, concurrency and authorisation
  rules from Phase 6 all apply unchanged.

## Loading, empty, error and partial states

- Empty case workspace prompts intake; each section has an explicit empty
  state ("No evidence recorded", "No policy match", …).
- Unknown case or citation ids render a safe 404 page (no stack traces).
- Tool failures are shown with their trace ids; a retry control is offered
  when the state machine allows it.
- All user/policy text is escaped Thymeleaf output — markup is displayed,
  never executed.

## Accessibility

Keyboard navigable, visible focus, labelled form controls, landmark
regions, sufficient contrast, responsive layout. See
`docs/ui-accessibility-checklist.md`.
