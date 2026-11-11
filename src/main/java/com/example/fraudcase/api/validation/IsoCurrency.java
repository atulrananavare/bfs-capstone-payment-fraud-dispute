package com.example.fraudcase.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value must be an ISO 4217 alphabetic currency code recognised by
 * {@link java.util.Currency} (e.g. {@code INR}, {@code USD}).
 */
@Documented
@Constraint(validatedBy = IsoCurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoCurrency {

    String message() default "must be a valid ISO 4217 currency code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
