package com.example.fraudcase.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects markup/script-style input: HTML/XML tags, {@code javascript:}
 * URIs and inline event-handler attributes. Free text fields are treated as
 * untrusted data and encoded on output; markup is never legitimate input.
 */
@Documented
@Constraint(validatedBy = NoUnsafeMarkupValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoUnsafeMarkup {

    String message() default "must not contain markup or script-style content";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
