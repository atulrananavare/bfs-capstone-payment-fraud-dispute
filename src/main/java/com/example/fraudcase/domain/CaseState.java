package com.example.fraudcase.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral lifecycle of a fraud/dispute case.
 *
 * <p>Allowed transitions:
 * <ul>
 *   <li>NEW -&gt; ENRICHING</li>
 *   <li>ENRICHING -&gt; READY_FOR_REVIEW | INCOMPLETE</li>
 *   <li>INCOMPLETE -&gt; ENRICHING | CLOSED</li>
 *   <li>READY_FOR_REVIEW -&gt; UNDER_REVIEW</li>
 *   <li>UNDER_REVIEW -&gt; ROUTED | CLOSED</li>
 *   <li>ROUTED -&gt; CLOSED</li>
 *   <li>CLOSED -&gt; (terminal)</li>
 * </ul>
 *
 * <p>Transitions are enforced by application/domain logic only. A model must
 * never set state directly.
 */
public enum CaseState {

    NEW,
    ENRICHING,
    INCOMPLETE,
    READY_FOR_REVIEW,
    UNDER_REVIEW,
    ROUTED,
    CLOSED;

    private static final Map<CaseState, Set<CaseState>> ALLOWED_TRANSITIONS = Map.of(
            NEW, EnumSet.of(ENRICHING),
            ENRICHING, EnumSet.of(READY_FOR_REVIEW, INCOMPLETE),
            INCOMPLETE, EnumSet.of(ENRICHING, CLOSED),
            READY_FOR_REVIEW, EnumSet.of(UNDER_REVIEW),
            UNDER_REVIEW, EnumSet.of(ROUTED, CLOSED),
            ROUTED, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(CaseState.class)
    );

    public Set<CaseState> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED_TRANSITIONS.get(this));
    }

    public boolean canTransitionTo(CaseState target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
