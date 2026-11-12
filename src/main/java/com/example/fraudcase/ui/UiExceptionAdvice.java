package com.example.fraudcase.ui;

import com.example.fraudcase.application.CaseNotFoundException;
import com.example.fraudcase.application.RecommendationNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Safe error pages for the UI controllers. No stack traces, internals or
 * provider details ever reach the rendered page.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(basePackages = "com.example.fraudcase.ui")
public class UiExceptionAdvice {

    @ExceptionHandler({CaseNotFoundException.class,
            RecommendationNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Model model) {
        model.addAttribute("errorTitle", "Not found");
        model.addAttribute("errorMessage",
                "The requested case or resource does not exist.");
        return "error/404";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(Model model) {
        model.addAttribute("errorTitle", "Invalid request");
        model.addAttribute("errorMessage",
                "The request could not be understood or was not permitted.");
        return "error/4xx";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Model model) {
        model.addAttribute("errorTitle", "Unavailable");
        model.addAttribute("errorMessage",
                "The operation could not be completed safely. "
                        + "No changes were committed.");
        return "error/5xx";
    }
}
