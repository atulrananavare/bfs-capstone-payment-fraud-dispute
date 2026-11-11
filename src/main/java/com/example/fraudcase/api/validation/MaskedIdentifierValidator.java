package com.example.fraudcase.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Validates masked/tokenised customer references such as
 * {@code CUST-••••-4821}.
 */
public class MaskedIdentifierValidator implements ConstraintValidator<MaskedIdentifier, String> {

    private static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9 •*xX_.:\\-]+");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // @NotBlank reports the mandatory-field violation
        }
        if (value.length() > MAX_LENGTH || !ALLOWED_CHARACTERS.matcher(value).matches()) {
            return false;
        }
        if (!MaskingSupport.containsMaskToken(value)) {
            return false;
        }
        return !MaskingSupport.containsUnmaskedIdentifier(value);
    }
}
