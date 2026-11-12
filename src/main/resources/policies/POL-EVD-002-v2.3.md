---
documentId: POL-EVD-002
title: Evidence Sufficiency and Manual Review Standard
version: "2.3"
effectiveDate: 2026-09-15
owner: Fraud Review Standards
classification: SYNTHETIC_TRAINING
status: CURRENT
---

## 1 Purpose

Synthetic training standard describing when a case must abstain from a
substantive classification and route to manual review.

## 2 Missing evidence

Mandatory transaction evidence that is absent or not found prevents any
safe classification. Record the specific missing items; do not estimate or
invent values.

## 3 Contradictory evidence

When a denial claim is contradicted by multiple independent benign signals
(trusted device, consistent location, successful authentication), treat the
evidence as contradictory and route to manual review rather than resolving
the contradiction by guessing.

## 4 Insufficient evidence

If the assembled evidence cannot support any classification, produce
INSUFFICIENT_EVIDENCE with MANUAL_REVIEW routing. Abstention is always
safer than an unsupported answer.

## 5 Unavailable policy context

When a procedural recommendation requires policy grounding and no current
applicable policy passage can be retrieved, record that policy guidance is
unavailable and route to manual review. Never fabricate policy content.
