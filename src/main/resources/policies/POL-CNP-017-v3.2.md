---
documentId: POL-CNP-017
title: Card-Not-Present Dispute Handling Procedure
version: "3.2"
effectiveDate: 2026-10-01
owner: Payment Fraud Operations
classification: SYNTHETIC_TRAINING
status: CURRENT
supersedes: "3.1"
---

## 1 Purpose

This synthetic training document describes how card-not-present dispute
cases are triaged inside the training capstone. It contains no real bank
procedure and exists only to exercise grounded recommendation behaviour.

## 2 Evidence expectations

Analysts and assistants should assemble, at minimum, the transaction record,
device-trust signals, recent location comparisons, merchant risk profile and
prior dispute history before recommending a route. Each element must come
from the approved evidence tools, never from assumption.

## 3.1 Initial triage

Confirm the transaction is posted and card-not-present. Establish whether the
customer denies the transaction or does not recognise the merchant, and
whether the payment card remains in the customer's possession.

## 3.2 Initial fraud routing

When a customer denies a card-not-present transaction and independently
sourced evidence indicates significant device or location anomalies, route
the case for fraud investigation.

If authentication evidence is unavailable, record it as missing evidence
rather than assuming authentication success or failure.

The recommendation remains subject to analyst review.

## 3.3 Authentication gaps

An unavailable authentication outcome is a gap, not a result. It must be
carried in missing information. A failed authentication outcome is an
anomaly that supports fraud-investigation routing; a successful outcome is a
benign signal that may contradict a denial claim.

## 3.4 Review requirement

Every card-not-present recommendation is advisory and must be reviewed by an
authorised fraud analyst before any operational step is taken. No automated
action on the account or the customer is authorised by this document.
