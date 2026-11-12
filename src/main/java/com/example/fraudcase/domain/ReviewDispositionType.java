package com.example.fraudcase.domain;

/**
 * Strongly-typed human review disposition. The reviewer records a case
 * disposition only — no financial or customer-impacting action is ever
 * executed by any value here.
 */
public enum ReviewDispositionType {

    /** Reviewer agrees the case proceeds to the recommended operational
     *  review/investigation queue. Case becomes ROUTED. */
    ESCALATE,

    /** Reviewer requires additional evidence before a final disposition.
     *  Case becomes INCOMPLETE (existing ENRICHING path applies later). */
    REQUEST_MORE_INFORMATION,

    /** Reviewer selects a different approved {@link RecommendedRoute}. */
    REROUTE,

    /** Reviewer disagrees with the recommendation and supplies an explicit
     *  human route. The original recommendation remains untouched. */
    OVERRIDE,

    /** Reviewer closes the case where domain rules permit. Closure records
     *  disposition only — it never implies a banking action ran. */
    CLOSE
}
