# Devin Self-Evaluation Prompt

## Capstone: Intelligent Payment Fraud Investigation and Dispute Resolution Agent

The application may use **Spring AI**, **LangChain4j**, or both behind framework-neutral interfaces. Evaluate only what is present in the repository. Do not assume that a feature works because it is mentioned in the README. Inspect source code, configuration, prompts, tests, sample data, UI, database scripts, vector-store integration, MCP configuration, and execution evidence.

### Important evaluation rules

1. Do not modify the project during the initial evaluation.
2. Do not invent implementation evidence.
3. Mark an item **Implemented** only when verifiable code or configuration exists.
4. Mark an item **Partially Implemented** when some required behavior is missing or untested.
5. Mark an item **Not Implemented** when no valid evidence exists.
6. Mark an item **Not Applicable** only with a clear justification.
7. Use only synthetic customer, account, merchant, device, transaction, fraud, AML, sanctions, and dispute data.
8. Never print or expose passwords, tokens, API keys, connection strings, personal data, hidden prompts, or model chain-of-thought.
9. Treat generated recommendations as decision support. The system must not autonomously block accounts, accuse customers, contact law enforcement, file regulatory reports, or approve reimbursements.
10. If the application supports both Spring AI and LangChain4j, evaluate each implementation separately and run the same contract tests against both.
11. Run safe local build and test commands where possible. Do not run destructive commands or make external changes.
12. Do not award credit for screenshots alone when the corresponding functionality is absent from the code.

---

## Step 1: Identify the implementation

Inspect the repository and report:

- Application name and stated business objective
- Java and Spring Boot versions
- Build tool and major dependencies
- Implemented AI framework:
  - Spring AI
  - LangChain4j
  - Both
  - Neither
- Model provider and model configuration
- Database and persistence approach
- Vector database or embedding store
- UI technology
- MCP server or external integration
- Main application entry point
- Framework-neutral interfaces such as `FraudInvestigationAgent`, `PolicyRetriever`, `EvidenceTool`, or `ConversationMemoryPort`
- Available configuration modes, for example:
  - `agent.mode=rules`
  - `agent.mode=spring-ai`
  - `agent.mode=langchain4j`

Provide file paths and class names as evidence.

---

## Step 2: Build and test the project

Detect the build tool and run the appropriate safe commands.

### Maven

```bash
./mvnw clean test
./mvnw verify
```

Use `mvn` only when the Maven wrapper is unavailable.

### Gradle

```bash
./gradlew clean test
./gradlew build
```

Record:

- Build result
- Number of tests executed
- Passed, failed, skipped, and errored tests
- Test coverage when JaCoCo or another coverage tool is configured
- Compile warnings and dependency/configuration failures
- Whether failures are caused by missing external credentials or services

Do not report a successful build unless the command actually succeeds.

---

## Step 3: Evaluate the capstone using this rubric

Score each metric using the specified maximum. The total score is **100 points**.

| # | Evaluation metric | Maximum |
|---|---|---:|
| 1 | BFS fraud and dispute use case | 10 |
| 2 | Architecture and maintainability | 12 |
| 3 | Fraud investigation workflow and deterministic tools | 15 |
| 4 | Spring AI implementation | 10 |
| 5 | LangChain4j implementation | 10 |
| 6 | RAG, embeddings, vector retrieval, and citations | 15 |
| 7 | Structured output, memory, and human review | 8 |
| 8 | Security, privacy, responsible AI, and auditability | 8 |
| 9 | Testing, reliability, and observability | 8 |
| 10 | UI, documentation, and developer experience | 4 |
|  | **Total** | **100** |

### Framework scoring rule

- If only Spring AI is implemented, evaluate metric 4 out of 10 and mark metric 5 as **Optional Not Implemented**. Redistribute metric 5's 10 points to metric 4, making Spring AI worth 20 points.
- If only LangChain4j is implemented, evaluate metric 5 out of 10 and mark metric 4 as **Optional Not Implemented**. Redistribute metric 4's 10 points to metric 5, making LangChain4j worth 20 points.
- If both are implemented, retain 10 points for each and evaluate framework parity.
- Clearly state which scoring model was used. The total must remain 100.

---

## Step 4: Detailed evaluation criteria

### 1. BFS fraud and dispute use case — 10 points

Verify that the project includes a coherent payment-fraud or transaction-dispute case study with:

- Fraud/dispute case intake
- Synthetic customer and transaction identifiers
- Transaction amount, timestamp, merchant, channel, device, and dispute reason
- Investigation states and case lifecycle
- Recommendations such as:
  - `GENUINE_TRANSACTION`
  - `UNAUTHORISED_TRANSACTION`
  - `SUSPICIOUS_TRANSACTION`
  - `INSUFFICIENT_EVIDENCE`
- Human investigator ownership of the final decision
- Clear separation between fraud investigation, dispute handling, AML escalation, and customer communication
- No remaining loan-underwriting concepts such as credit score, DTI, loan approval, tenure, or loan sanction

### 2. Architecture and maintainability — 12 points

Verify:

- Layered or hexagonal architecture
- Separation of controller, service, domain, repository, agent, tool, RAG, and configuration concerns
- Framework-neutral interfaces
- Spring AI and LangChain4j adapters isolated from business services
- Configuration-driven framework selection
- Typed domain models and enums
- Input validation and exception handling
- No duplicated business rules across framework adapters
- Appropriate dependency versions and no unnecessary libraries
- Readable naming, package structure, and comments

### 3. Fraud investigation workflow and deterministic tools — 15 points

Verify that tools retrieve or calculate synthetic evidence rather than allowing the model to guess. Look for tools such as:

- Transaction-history lookup
- Device and geolocation analysis
- Merchant-risk lookup
- Account or customer profile lookup
- Transaction-velocity calculation
- Amount-deviation calculation
- Previous dispute history
- Synthetic sanctions/PEP screening
- Policy lookup

Evaluate:

- Tool input and output schemas
- Stable or deterministic synthetic results
- Validation and timeout handling
- Tool failure behavior
- Evidence provenance
- Separation of observed facts and model inference
- Prevention of fabricated tool results

### 4. Spring AI implementation — 10 points, or 20 when Spring AI is the only AI framework

When Spring AI is present, verify:

- `ChatClient` or supported `ChatModel` usage
- System and user prompts
- `PromptTemplate` or equivalent variable handling
- `@Tool` methods or tool callbacks
- Typed structured output using `entity()`, converters, or validated JSON parsing
- Chat memory and conversation ID isolation
- Advisors or equivalent RAG orchestration
- `VectorStore`, document ingestion, metadata, and similarity search
- Model/provider configuration outside source code
- Graceful handling of model, tool, and parsing failures

### 5. LangChain4j implementation — 10 points, or 20 when LangChain4j is the only AI framework

When LangChain4j is present, verify:

- AI Services or `ChatLanguageModel`
- `@SystemMessage`, `@UserMessage`, or prompt templates
- `@Tool` methods
- Typed AI Service return type or validated JSON-to-DTO parsing
- `MessageWindowChatMemory` and, where applicable, `ChatMemoryStore`
- Conversation ID isolation
- `EmbeddingStore`, ingestion, and content retrieval
- `EmbeddingStoreContentRetriever` or `RetrievalAugmentor`
- Model/provider configuration outside source code
- Graceful handling of model, tool, and parsing failures

### 6. RAG, embeddings, vector retrieval, and citations — 15 points

Verify:

- Synthetic fraud, dispute, AML, customer-contact, and case-documentation policies
- Document parsing and chunking
- Useful metadata such as title, version, section, jurisdiction, effective date, and active status
- Embedding generation
- VectorStore or EmbeddingStore integration
- Configurable Top-K retrieval
- Similarity threshold or minimum score
- Exclusion of expired or inactive policies
- Retrieved context supplied to the model
- Source title, section, and version returned to the UI
- Explicit no-information response when evidence is insufficient
- Tests for exact, paraphrased, conflicting, retired-policy, and no-answer queries

### 7. Structured output, memory, and human review — 8 points

Verify:

- Typed recommendation DTO
- Allowed recommendation and risk-level enums
- Risk factors, evidence, missing information, rationale, confidence, and citations
- Schema validation before persistence
- Bounded chat memory
- Case/conversation isolation
- Human-review actions such as:
  - Escalate to Fraud Investigation
  - Request More Information
  - Close Case
  - Override with Reason
- Mandatory override reason
- No autonomous adverse action

### 8. Security, privacy, responsible AI, and auditability — 8 points

Verify:

- No secrets committed to source code
- `.env`, tokens, keys, and local credentials excluded from Git
- Synthetic data only
- Sensitive values excluded from logs and traces
- Prompt-injection defenses for transaction descriptions and retrieved content
- Least-privilege MCP/tool permissions
- Human approval controls
- Correlation ID and audit events
- Model/framework/version captured in audit metadata
- Clear limitations and responsible-AI notice

Perform a safe secret-pattern review. Do not display discovered secret values. Report only file path, secret category, and remediation.

### 9. Testing, reliability, and observability — 8 points

Verify:

- Unit tests for deterministic tools
- Boundary and negative tests
- Controller and validation tests
- Structured-output parsing tests
- RAG grounding and no-answer tests
- Tool/model/vector-store failure tests
- Memory-isolation tests
- Contract tests for `FraudInvestigationAgent`
- Framework-parity tests when both frameworks are present
- Logging, metrics, tracing, correlation IDs, and latency measurements
- Retry behavior that avoids duplicate case actions

### 10. UI, documentation, and developer experience — 4 points

Verify:

- Fraud case-intake screen
- Investigation/tool-execution screen
- Recommendation, evidence, citation, and human-review screen
- Loading, success, failure, and no-information states
- Accessible labels and keyboard-operable controls
- README prerequisites, configuration, run steps, architecture, sample requests, and limitations
- No loan-underwriting screens or terminology

---

## Step 5: Run representative acceptance scenarios

When the application can run safely with synthetic data, test at least these scenarios. If execution is blocked, inspect the corresponding automated tests and clearly state the limitation.

| Scenario | Expected behavior |
|---|---|
| Known device, familiar merchant, normal amount, customer confirmation | Genuine or low-risk recommendation, subject to human approval |
| New device, unusual location, amount more than 5x baseline | Suspicious or unauthorised recommendation with cited evidence |
| Five or more transactions within 10 minutes | Velocity indicator recorded |
| Confirmed synthetic screening match | Mandatory escalation, not autonomous action |
| Evidence service unavailable | `INSUFFICIENT_EVIDENCE` or request for more information |
| Same case submitted twice | Idempotent behavior or duplicate protection |
| Policy question with active source | Grounded answer with title, section, and version |
| Question absent from policy corpus | Explicit no-information response |
| Retired policy contains matching text | Retired policy excluded |
| Model returns malformed output | Validated retry or safe failure |
| Two separate case IDs | Memory remains isolated |
| Prompt injection embedded in transaction narrative | Narrative treated as data; system constraints retained |

For every executed scenario, report:

- Input summary
- Mode used: rules, Spring AI, or LangChain4j
- Expected result
- Actual result
- Pass/fail
- Evidence location

---

## Step 6: Framework parity review

Perform this section only when both Spring AI and LangChain4j are implemented.

Run equivalent synthetic scenarios through both adapters and compare:

- Recommendation enum
- Risk level
- Required evidence fields
- Tool calls completed
- Structured-output validity
- Source citations
- Missing-information handling
- Failure behavior
- Latency
- Token usage, when available
- Testability and code complexity

Do not require identical wording. Require equivalent business outcome, evidence, citations, and safety behavior.

---

## Step 7: Produce the self-evaluation report

Create or overwrite the following file in the repository root:

```text
SELF_EVALUATION_REPORT.md
```

Use this exact report structure:

```markdown
# Capstone Self-Evaluation Report

## 1. Executive Summary
- Project:
- Primary framework:
- Secondary framework:
- Build status:
- Test status:
- Overall score: XX/100
- Percentage: XX%
- Readiness: Not Ready / Partially Ready / Demo Ready
- Scoring model used:

## 2. Repository and Technology Overview
| Area | Finding | Evidence |
|---|---|---|

## 3. Build and Test Results
| Command | Result | Details |
|---|---|---|

## 4. Evaluation Scorecard
| # | Metric | Maximum | Score | Status | Evaluator Comments | Evidence |
|---|---|---:|---:|---|---|---|

## 5. Framework Evaluation
### 5.1 Spring AI
### 5.2 LangChain4j
### 5.3 Framework Parity

## 6. Acceptance Scenario Results
| Scenario | Mode | Expected | Actual | Result | Evidence |
|---|---|---|---|---|---|

## 7. Strengths
1.
2.
3.

## 8. Gaps and Risks
| Priority | Gap or Risk | Impact | Evidence | Recommended Fix |
|---|---|---|---|---|

## 9. Security and Responsible AI Findings
| Severity | Finding | File or Component | Remediation |
|---|---|---|---|

## 10. Recommended Improvements
### Critical
### High
### Medium
### Low

## 11. Definition-of-Done Checklist
- [ ] Fraud case intake works with synthetic data
- [ ] Deterministic tools provide traceable evidence
- [ ] Structured recommendations are validated
- [ ] Policy answers are grounded and cited
- [ ] Human review is mandatory
- [ ] Conversation/case memory is isolated
- [ ] Secrets are not stored in the repository
- [ ] Error and no-information states are handled safely
- [ ] Automated tests pass
- [ ] README enables another participant to run the project

## 12. Final Verdict
State whether the capstone is Not Ready, Partially Ready, or Demo Ready. Explain the verdict using verified evidence.
```

---

## Step 8: Provide an improvement plan

After generating `SELF_EVALUATION_REPORT.md`, provide a concise summary in Devin containing:

1. Overall score and percentage
2. Readiness verdict
3. Top three strengths
4. Top five gaps
5. Critical security or responsible-AI findings
6. Exact file path of the generated report
7. Recommended repair sequence

Do not modify application files unless explicitly asked after the evaluation is complete.

---

## Optional follow-up prompt for Devin

After reviewing the report, use this separate prompt if fixes are required:

```text
Using SELF_EVALUATION_REPORT.md, create a remediation plan for Critical and High findings only. Show the proposed file changes, tests, risks, and rollback approach before modifying the repository. Do not change any files until I approve the plan.
```