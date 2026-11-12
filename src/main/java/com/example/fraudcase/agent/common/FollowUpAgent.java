package com.example.fraudcase.agent.common;

import com.example.fraudcase.domain.FollowUpAnswer;
import com.example.fraudcase.domain.FollowUpContext;

/**
 * Framework-neutral conversational follow-up contract — the counterpart of
 * {@link FraudCaseProcessor} for case-scoped messages. Every AI adapter
 * implements this so {@code CaseMessageService} never depends on a specific
 * framework.
 *
 * <p>Implementations must never execute actions, change case state or
 * relax the human-review obligation, and must reject output that claims
 * otherwise.
 */
public interface FollowUpAgent {

    FollowUpAnswer answer(FollowUpContext context);
}
