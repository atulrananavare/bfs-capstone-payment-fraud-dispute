---
documentId: POL-DUP-009
title: Duplicate Transaction Handling Procedure
version: "2.0"
effectiveDate: 2026-07-01
owner: Payment Operations
classification: SYNTHETIC_TRAINING
status: CURRENT
---

## 1 Purpose

Synthetic training guidance for suspected duplicate processing of a single
underlying transaction.

## 2 Duplicate fingerprint

A confirmed duplicate posting fingerprint indicates the same transaction was
processed more than once by the merchant or acquirer. The fingerprint is
produced by the deterministic transaction tool and must not be inferred
from the customer statement alone.

## 3 Payment-operations routing

When a duplicate fingerprint is confirmed, route the case to payment
operations for a posting review rather than to fraud investigation, because
duplicate processing is an operational defect, not evidence of account
takeover.

## 4 Analyst confirmation

An analyst must confirm the duplicate against both postings before any
reversal workflow is initiated. The assistant only recommends the route;
no posting adjustment is performed automatically.
