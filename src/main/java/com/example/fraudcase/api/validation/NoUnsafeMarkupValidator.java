package com.example.fraudcase.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rejects markup/script-style content in free-text input. Output encoding is
 * additionally handled by the UI layer; this validator prevents markup from
 * entering the system at all.
 */
public class NoUnsafeMarkupValidator implements ConstraintValidator<NoUnsafeMarkup, String> {

    private static final List<Pattern> UNSAFE_PATTERNS = List.of(
            // HTML/XML-style tags, comments and declarations: <script, </x, <!doctype, <?
            Pattern.compile("<\\s*[/!?]?\\s*[a-zA-Z!?]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("data\\s*:\\s*text/html", Pattern.CASE_INSENSITIVE),
            // inline event-handler attributes: onclick=, onerror=, onload=
            Pattern.compile("\\bon[a-z]+\\s*=", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return UNSAFE_PATTERNS.stream().noneMatch(p -> p.matcher(value).find());
    }
}
