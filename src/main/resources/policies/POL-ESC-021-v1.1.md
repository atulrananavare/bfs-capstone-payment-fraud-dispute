---
documentId: POL-ESC-021
title: Suspicious Transaction Escalation Procedure
version: "1.1"
effectiveDate: 2026-09-01
owner: Payment Fraud Operations
classification: SYNTHETIC_TRAINING
status: CURRENT
---

## 1 Purpose

Synthetic training guidance for escalating cases that present multiple
independent fraud anomalies without a clean customer denial.

## 2 Anomaly sources

Independent high-severity anomaly sources include device signals (unknown
or untrusted device), location comparisons (significant geographic anomaly),
authentication outcomes (explicit failure) and merchant risk profile
(blocklist match or high risk band).

## 3 Escalation rule

Two or more independently sourced high-severity anomalies justify fraud
investigation routing at high risk. Exactly one anomaly justifies
fraud-investigation routing at medium risk with reduced confidence and an
explicit note about limited corroboration.

## 4 Severity and confidence

Confidence should decrease when corroborating tools are unavailable. Never
treat an unexecuted or failed tool as a benign signal; record it as missing
evidence.
