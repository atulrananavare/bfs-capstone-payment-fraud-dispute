package com.example.fraudcase.agent.common;

import java.util.List;

/**
 * Structured output contract for a conversational follow-up answer.
 *
 * <p>{@code evidenceIds} may only name evidence persisted for THIS case and
 * {@code policyCitations} may only name chunk ids from the passages
 * retrieved for this turn or citations already validated for this case.
 * Every reference is re-validated against trusted sources — nothing the
 * model emits is trusted directly.
 */
public record AiFollowUpOutput(
        String caseId,
        String answer,
        List<String> evidenceIds,
        List<String> policyCitations,
        List<String> missingInformation,
        Boolean humanReviewRequired) {
}
