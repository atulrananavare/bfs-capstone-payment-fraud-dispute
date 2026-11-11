package com.example.fraudcase.api.validation;

import java.util.regex.Pattern;

/**
 * Shared helpers for masking-related validators.
 */
final class MaskingSupport {

    /**
     * A visible mask token: one or more '•' bullets, or 2+ consecutive
     * masking characters from {@code *}, {@code x}, {@code X}.
     */
    private static final Pattern MASK_TOKEN = Pattern.compile(".*(?:•|[*xX]{2,}).*");

    /**
     * An unmasked identifier-like digit run. Separators commonly used in
     * card/account numbers (spaces and dashes) are removed before matching so
     * that {@code 4111-1111-1111-1111} and {@code 4111 1111 1111 1111} are
     * both detected. A run of 9+ digits is treated as a likely raw
     * account/card identifier.
     */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile(".*\\d{9,}.*");

    private MaskingSupport() {
    }

    static boolean containsMaskToken(String value) {
        return value != null && MASK_TOKEN.matcher(value).matches();
    }

    static boolean containsUnmaskedIdentifier(String value) {
        if (value == null) {
            return false;
        }
        String normalised = SEPARATORS.matcher(value).replaceAll("");
        return LONG_DIGIT_RUN.matcher(normalised).matches();
    }
}
