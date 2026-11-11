package com.example.fraudcase.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Validates ISO 4217 alphabetic transaction-currency codes using the JDK's
 * currency registry, additionally excluding non-tender codes such as
 * {@code XXX} ("no currency") and {@code XTS} (testing).
 */
public class IsoCurrencyValidator implements ConstraintValidator<IsoCurrency, String> {

    private static final Pattern ISO_ALPHA3 = Pattern.compile("[A-Z]{3}");

    /**
     * ISO 4217 codes the JDK recognises but that are not transaction
     * currencies: {@code XXX} = "no currency", {@code XTS} = testing code.
     */
    private static final java.util.Set<String> NON_TENDER_CODES = java.util.Set.of("XXX", "XTS");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // @NotBlank reports the mandatory-field violation
        }
        if (!ISO_ALPHA3.matcher(value).matches() || NON_TENDER_CODES.contains(value)) {
            return false;
        }
        return Currency.getAvailableCurrencies().stream()
                .anyMatch(c -> c.getCurrencyCode().equals(value));
    }
}
