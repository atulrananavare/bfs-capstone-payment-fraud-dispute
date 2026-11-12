package com.example.fraudcase.agent.langchain4j;

import com.example.fraudcase.agent.common.ModelProcessingException;

/**
 * LangChain4j mode could not produce a recommendation at all:
 * provider/model failure, tool-loop bound exceeded, or another
 * adapter-internal error. Triggers the deterministic rules fallback
 * upstream — same contract as the Spring AI adapter.
 */
public class LangChain4jProcessingException extends ModelProcessingException {

    public LangChain4jProcessingException(String message) {
        super(message);
    }

    public LangChain4jProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
