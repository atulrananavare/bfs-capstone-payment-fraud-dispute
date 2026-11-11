package com.example.fraudcase.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value must be a masked/tokenised identifier: it must visibly contain a
 * mask token ({@code •}, {@code **}, {@code XX}…) and must not contain an
 * unmasked account/card-style digit run.
 */
@Documented
@Constraint(validatedBy = MaskedIdentifierValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaskedIdentifier {

    String message() default "must be a masked or tokenised reference; raw account/card identifiers are not permitted";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
