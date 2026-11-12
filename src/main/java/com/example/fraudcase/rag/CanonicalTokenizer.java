package com.example.fraudcase.rag;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic text → canonical-concept tokenizer shared by the embedding
 * model and the vector store. Maps inflections, synonyms and negation
 * phrases onto a small set of canonical tokens so paraphrased queries
 * still overlap the ingested policy text.
 *
 * <p>Deliberately simple and fully deterministic — identical input always
 * produces identical tokens, satisfying the offline-test requirement.
 */
public final class CanonicalTokenizer {

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "to", "of", "and", "or", "in", "on", "for", "with", "at", "as",
            "by", "it", "its", "this", "that", "my", "i", "says", "said",
            "do", "does", "did", "not", "no", "any", "some", "has", "have",
            "had", "s", "t", "if", "then", "when", "but", "so", "we",
            "they", "their", "from", "into", "than", "about", "all");

    /** Multi-word phrases are canonicalised before tokenisation. */
    private static final Map<String, String> PHRASES = Map.ofEntries(
            Map.entry("card not present", "cnp"),
            Map.entry("card-not-present", "cnp"),
            Map.entry("not recognised", "unrecognised"),
            Map.entry("not recognized", "unrecognised"),
            Map.entry("not familiar", "unrecognised"),
            Map.entry("does not recognise", "unrecognised"),
            Map.entry("does not recognize", "unrecognised"),
            Map.entry("denied by the customer", "deny customer"),
            Map.entry("denies the transaction", "deny transaction"),
            Map.entry("no longer valid", "withdrawn"),
            Map.entry("goods or services not received", "nonreceipt"));

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("seller", "merchant"),
            Map.entry("vendor", "merchant"),
            Map.entry("store", "merchant"),
            Map.entry("shop", "merchant"),
            Map.entry("retailer", "merchant"),
            Map.entry("unfamiliar", "unrecognised"),
            Map.entry("unknown", "unrecognised"),
            Map.entry("unrecognized", "unrecognised"),
            Map.entry("recognised", "recognised"),
            Map.entry("recognized", "recognised"),
            Map.entry("denies", "deny"),
            Map.entry("denied", "deny"),
            Map.entry("disputes", "dispute"),
            Map.entry("disputed", "dispute"),
            Map.entry("cardholder", "customer"),
            Map.entry("handset", "device"),
            Map.entry("phone", "device"),
            Map.entry("geographic", "location"),
            Map.entry("geography", "location"),
            Map.entry("auth", "authentication"),
            Map.entry("verified", "authentication"),
            Map.entry("purchase", "transaction"),
            Map.entry("payment", "transaction"),
            Map.entry("charge", "transaction"),
            Map.entry("posting", "transaction"),
            Map.entry("routing", "route"),
            Map.entry("routed", "route"),
            Map.entry("escalate", "escalation"),
            Map.entry("cancel", "cancellation"),
            Map.entry("cancelled", "cancellation"),
            Map.entry("canceled", "cancellation"),
            Map.entry("deliver", "fulfilment"),
            Map.entry("delivered", "fulfilment"),
            Map.entry("delivery", "fulfilment"),
            Map.entry("fulfil", "fulfilment"),
            Map.entry("fulfill", "fulfilment"),
            Map.entry("fulfillment", "fulfilment"),
            Map.entry("reimburse", "refund"),
            Map.entry("reimbursement", "refund"),
            Map.entry("double", "duplicate"),
            Map.entry("repeated", "duplicate"),
            Map.entry("reviewer", "analyst"),
            Map.entry("human", "analyst"),
            Map.entry("unauthorised", "unauthorised"),
            Map.entry("unauthorized", "unauthorised"),
            Map.entry("fraudulent", "fraud"),
            Map.entry("anomalies", "anomaly"),
            Map.entry("triaged", "triage"));

    private CanonicalTokenizer() {
    }

    /** Ordered canonical tokens for the given text. */
    public static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = " " + text.toLowerCase() + " ";
        for (var entry : PHRASES.entrySet()) {
            normalized = normalized.replace(" " + entry.getKey() + " ",
                    " " + entry.getValue() + " ");
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.isBlank() || STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(SYNONYMS.getOrDefault(token, stem(token)));
        }
        return tokens;
    }

    /** Tiny deterministic stem: drop common suffixes so inflections match. */
    private static String stem(String token) {
        if (token.length() > 4 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 4 && token.endsWith("ing")) {
            return token.substring(0, token.length() - 3);
        }
        if (token.length() > 3 && token.endsWith("ed")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 3 && token.endsWith("s")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}
