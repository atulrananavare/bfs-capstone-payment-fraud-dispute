package com.example.fraudcase.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects free text that appears to carry unmasked payment data or
 * credentials: payment-card-like digit runs, API keys, bearer tokens and
 * private-key material. Such values are never legitimate conversation
 * input and must not be persisted.
 */
@Documented
@Constraint(validatedBy = NoSensitiveContentValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoSensitiveContent {

    String message() default "must not contain payment card numbers, account numbers or credentials";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
