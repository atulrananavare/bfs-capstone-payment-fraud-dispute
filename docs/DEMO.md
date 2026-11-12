# Demo Script — Fraud Case Agent

Repeatable, fully synthetic, no credentials needed (default `rules` mode).
Every artifact shown is deterministic — same inputs, same outputs.

## 0. Build & start

```bash
mvn clean package -DskipTests          # or mvn clean verify for the full suite
java -jar target/fraud-case-agent-0.1.0-SNAPSHOT.jar
# alternative port if 8080 is busy:
java -jar target/fraud-case-agent-0.1.0-SNAPSHOT.jar --server.port=8085
```

## 1. Health & observability surface

```bash
curl -s localhost:8080/actuator/health     # {"status":"UP"}
curl -s localhost:8080/actuator/info       # static app metadata only
curl -s localhost:8080/actuator/metrics    # meter list (fraudcase.* after flows)
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/env   # 404 — not exposed
```

## 2. Create a case (UI)

- Open `http://localhost:8080/` → **View Cases** (worklist) →
  **New Case Intake**.
- Submit the masked synthetic sample: ref `TXN-2026-784102`,
  `MERCHANT_NOT_RECOGNISED`, merchant `NORTHSTAR DIGITAL ••••73`,
  customer `CUST-••••-4821`, amount `48750.00 INR`.
- Redirect lands on the case workbench (the id is in the URL;
  `/ui/cases` lists it).

## 3. Investigation

- **Start Investigation** → the bounded plan runs: 6 allow-listed
  read-only tools (transaction, device, location, merchant, prior
  disputes, SLA), persisted traces with status/duration.
- Recommendation: `UNAUTHORISED_TRANSACTION` · HIGH ·
  `FRAUD_INVESTIGATION` · confidence, rule ids (`R003`), evidence links,
  missing information (auth outcome unavailable — acknowledged, never
  invented), permanent **advisory — human review required** label.
- Evidence screen shows claim vs tool evidence with provenance.
- Policy screen shows exact retrieved passages/citations (or an explicit
  no-match abstention — never fabricated).

## 4. Alternative business paths (repeatable)

- `TXN-5555-000002` → `DUPLICATE_PROCESSING` → `PAYMENT_OPERATIONS` (R002).
- `TXN-4040-404040` → `INSUFFICIENT_EVIDENCE` → `MANUAL_REVIEW` (R001),
  state `INCOMPLETE`, re-investigation permitted.
- `AMOUNT_DISPUTE` + `merchantRecognised=RECOGNISED` → `MERCHANT_DISPUTE`
  → `DISPUTE_OPERATIONS` (R004).

## 5. Mandatory human review

- Review screen: trusted reviewer identity (read-only, from config —
  `TRAINING-REVIEWER-001`), disposition choices, mandatory reason (T12),
  acknowledgement checkboxes, append-only history preview.
- Submit `ESCALATE` + reason + acknowledgements → case `ROUTED`;
  recommendation stays immutable; audit `REVIEW_*` + state change.
- Or `CLOSE` → `CLOSED` (terminal — further reviews rejected 409).

## 6. Failure & security (one-liners)

```bash
# unknown case → safe 404
curl -s localhost:8080/api/cases/FC-DEADBEEF | python3 -m json.tool
# review without reason → 400, nothing persisted
curl -s -X POST localhost:8080/api/cases/<id>/reviews -H 'Content-Type: application/json' \
  -d '{"disposition":"ESCALATE","reason":"","recommendationId":"<rec>"}' -w '%{http_code}\n'
# duplicate investigation → 409 (invalid transition)
curl -s -X POST localhost:8080/api/cases/<id>/investigations -w '%{http_code}\n'
# browser POST without CSRF → 403; JSON API exempt by design
```

Prompt-injection note: customer statements and messages are untrusted
data — injection text cannot change state, bypass review or trigger
actions (see `Phase8EndToEndTest`, eval E16/E23).

## 7. Case-scoped memory & spring-ai mode

Requires `--capstone.processor-mode=spring-ai` + `ANTHROPIC_API_KEY`.
Two cases keep strictly disjoint histories (`POST/GET .../messages`);
the model sees only the bounded 12-message window for its own case. With
an invalid/absent provider the app degrades deterministically:
`RULES_FALLBACK` recommendations and `SAFE_FALLBACK` answers — no crash,
no leaked error detail.

## 8. Metrics & evaluation

```bash
curl -s localhost:8080/actuator/metrics/fraudcase.investigations.completed
curl -s localhost:8080/actuator/metrics/fraudcase.reviews.submitted
curl -s localhost:8080/actuator/metrics/fraudcase.tools.invocations
# every response carries X-Request-Id — check response headers

mvn test -Dtest=Phase9EvaluationTest        # E01–E18 → target/evaluation-report.json
mvn test -Dtest=Phase8EndToEndTest          # T01–T12 regression, 14 checks
```

Suggested narrated order: health → UI worklist → intake → investigation
→ recommendation/evidence/policy → review → metrics → evaluation report.
Total: ~10 minutes.
