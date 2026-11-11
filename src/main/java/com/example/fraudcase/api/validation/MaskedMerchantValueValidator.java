package com.example.fraudcase.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * Validates masked/synthetic merchant descriptors such as
 * {@code NORTHSTAR DIGITAL ••••73}.
 */
public class MaskedMerchantValueValidator implements ConstraintValidator<MaskedMerchantValue, String> {

    private static final int MAX_LENGTH = 80;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[A-Za-z0-9 •*xX_.&',:\\-]+");

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
