package com.example.fraudcase.api;

import com.example.fraudcase.application.CaseNotFoundException;
import com.example.fraudcase.application.ConversationUnavailableException;
import com.example.fraudcase.application.InvestigationNotPermittedException;
import com.example.fraudcase.application.ProcessorModeUnavailableException;
import com.example.fraudcase.application.RecommendationNotFoundException;
import com.example.fraudcase.application.RecommendationValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Comparator;
import java.util.List;

/**
 * Safe global error handling. Responses never contain stack traces,
 * internal implementation details, provider information or echoed
 * sensitive input values.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(fe -> new ApiErrorResponse.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() == null ? "invalid value" : fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // Covers malformed JSON, unknown enum values and unparseable types.
        // The parser detail is logged, never returned to the caller.
        log.debug("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_REQUEST",
                "Request body could not be parsed: malformed JSON or unsupported value"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_REQUEST",
                "Request parameter has an unsupported value"));
    }

    @ExceptionHandler(CaseNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(CaseNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "CASE_NOT_FOUND",
                "The requested case does not exist"));
    }

    @ExceptionHandler(RecommendationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoRecommendation(RecommendationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "RECOMMENDATION_NOT_FOUND",
                "No recommendation exists for this case"));
    }

    @ExceptionHandler(InvestigationNotPermittedException.class)
    public ResponseEntity<ApiErrorResponse> handleNotPermitted(InvestigationNotPermittedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "INVESTIGATION_NOT_PERMITTED",
                "The case state does not allow starting an investigation"));
    }

    @ExceptionHandler(RecommendationValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleRejected(RecommendationValidationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "RECOMMENDATION_REJECTED",
                "The produced recommendation failed validation and was not persisted"));
    }

    @ExceptionHandler(ConversationUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleConversationUnavailable(
            ConversationUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "CONVERSATION_UNAVAILABLE",
                "Conversational follow-up requires the spring-ai processor mode"));
    }

    @ExceptionHandler(ProcessorModeUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleProcessorUnavailable(ProcessorModeUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "PROCESSOR_MODE_UNAVAILABLE",
                "The configured processor mode is not available"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "An unexpected error occurred"));
    }
}
