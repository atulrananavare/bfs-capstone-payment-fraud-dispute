package com.example.fraudcase.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Conservative detector for values that must never enter conversation
 * history: 13-19 digit runs (payment-card-like), well-known API-key
 * shapes, bearer tokens and PEM private-key blocks. False positives are
 * possible by design — rejecting ambiguous input is safer than persisting
 * a secret.
 */
public class NoSensitiveContentValidator
        implements ConstraintValidator<NoSensitiveContent, String> {

    private static final List<Pattern> PROHIBITED = List.of(
            // 13-19 digits, optionally separated by single spaces/dashes —
            // matches payment-card-shaped input, not normal prose.
            Pattern.compile("(?:\\d[ -]?){13,19}"),
            // Provider-style API keys: sk-..., sk_live_..., api_key=...
            Pattern.compile("(?i)\\b(sk_live_|sk_test_|sk-|api[_-]?key\\s*[:=]\\s*[\"']?)[A-Za-z0-9_\\-]{8,}"),
            // Bearer tokens.
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{10,}"),
            // PEM private-key material.
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return PROHIBITED.stream().noneMatch(p -> p.matcher(value).find());
    }
}
