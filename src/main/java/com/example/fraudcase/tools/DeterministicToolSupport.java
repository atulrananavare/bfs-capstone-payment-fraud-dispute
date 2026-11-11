package com.example.fraudcase.tools;

import com.example.fraudcase.config.CapstoneProperties;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Shared base for deterministic evidence tools: the configured fixture seed,
 * the operational clock (metadata only — never evidence content) and
 * result-id helpers.
 */
abstract class DeterministicToolSupport {

    private final String seed;
    private final Clock clock;

    protected DeterministicToolSupport(CapstoneProperties properties, Clock clock) {
        this.seed = properties.getTools().getDeterministicSeed();
        this.clock = clock;
    }

    protected String seed() {
        return seed;
    }

    protected OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    protected long hash(String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = seed;
        System.arraycopy(parts, 0, all, 1, parts.length);
        return Determinism.stableHash(all);
    }

    protected String resultId(String toolName, String... keyParts) {
        String[] all = new String[keyParts.length + 2];
        all[0] = seed;
        all[1] = toolName;
        System.arraycopy(keyParts, 0, all, 2, keyParts.length);
        return Determinism.shortId("TR", 16, all);
    }
}
