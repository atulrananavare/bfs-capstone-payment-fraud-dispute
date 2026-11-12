package com.example.fraudcase.agent.common;

import java.util.regex.Pattern;

/**
 * Shared safety check: text that claims an autonomous financial,
 * account-control or customer-contact action was executed. Conservative on
 * purpose — a false positive routes to the safe deterministic fallback.
 * Used by recommendation validation and by conversational follow-up
 * answers; the rule is identical in every path.
 */
public final class ActionClaimGuard {

    private static final Pattern ACTION_CLAIM = Pattern.compile(
            "(i (have|will|shall)|we (have|will|shall))?( ?(already|now))? ?"
                    + "(blocked|refunded|credited|transferred|froze|frozen|contacted|filed|submitted|debited)"
                    + " (the |a |an )?(card|account|customer|cardholder|funds|dispute|claim|report|refund)"
                    + "|(card|account) (has been|is now|was) (blocked|frozen|closed)"
                    + "|(refund|credit|transfer) (has been|was|is) (issued|processed|initiated|completed|posted)"
                    + "|(customer|cardholder) (has been|was|is) (contacted|notified|emailed|called)"
                    + "|(regulatory|aml|kyc) (report|filing) (has been|was|is) (filed|submitted)",
            Pattern.CASE_INSENSITIVE);

    private ActionClaimGuard() {
    }

    /** True when {@code text} claims a prohibited action was executed. */
    public static boolean claimsExecutedAction(String text) {
        return text != null && ACTION_CLAIM.matcher(text).find();
    }
}
