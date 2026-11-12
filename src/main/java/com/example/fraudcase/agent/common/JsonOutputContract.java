package com.example.fraudcase.agent.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Framework-neutral structured-output contract for adapters that do not
 * have a {@code BeanOutputConverter} equivalent — produces the format
 * instructions appended to the system prompt and parses the model's JSON
 * back into the shared {@code Ai*Output} records.
 *
 * <p>Same contract for every adapter: identical field names, identical
 * rejection of unparseable output via {@link InvalidModelOutputException}.
 */
public final class JsonOutputContract {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonOutputContract() {
    }

    /**
     * Format instructions derived from the target record's components —
     * the same information {@code BeanOutputConverter} embeds for the
     * Spring AI adapter, generated without framework dependencies.
     */
    public static String formatFor(Class<? extends Record> recordType) {
        String fields = java.util.Arrays.stream(recordType.getRecordComponents())
                .map(JsonOutputContract::describe)
                .collect(Collectors.joining(",\n"));
        return "Respond with a single valid JSON object matching this "
                + "structure (no markdown, no code fences, no commentary):\n{\n"
                + fields + "\n}\n"
                + "Use null only where a value is genuinely unknown; "
                + "lists may be empty.";
    }

    /**
     * Parses model text into {@code type}. Markdown code fences around the
     * JSON are stripped; any other unparseable output is an
     * {@link InvalidModelOutputException}.
     */
    public static <T extends Record> T parse(String text, Class<T> type) {
        String cleaned = stripFences(text == null ? "" : text.trim());
        try {
            T value = MAPPER.readValue(cleaned, type);
            if (value == null) {
                throw new InvalidModelOutputException("model output did not convert");
            }
            return value;
        } catch (InvalidModelOutputException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidModelOutputException(
                    "model output is not valid structured JSON", ex);
        }
    }

    private static String stripFences(String text) {
        String s = text;
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = nl < 0 ? "" : s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            s = end < 0 ? s : s.substring(0, end);
        }
        return s.trim();
    }

    private static String describe(RecordComponent c) {
        Class<?> t = c.getType();
        String type = t == String.class ? "string"
                : t == Boolean.class || t == Boolean.TYPE ? "boolean"
                : Number.class.isAssignableFrom(t) || t.isPrimitive() ? "number"
                : List.class.isAssignableFrom(t) ? "array of strings"
                : "string";
        return "  \"" + c.getName() + "\": <" + type + ">";
    }
}
