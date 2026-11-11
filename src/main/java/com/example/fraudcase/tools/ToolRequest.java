package com.example.fraudcase.tools;

/**
 * Typed tool request. {@link #canonical()} produces the stable string that
 * is hashed into the tool trace's requestHash — it must contain only
 * already-masked values, never raw sensitive data.
 */
public interface ToolRequest {

    String canonical();
}
