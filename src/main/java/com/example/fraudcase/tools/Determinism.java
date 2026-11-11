package com.example.fraudcase.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable hashing for deterministic synthetic evidence. Identical inputs
 * (seed + business keys) always produce identical output. No Math.random,
 * no UUIDs, no wall-clock influence on business evidence.
 */
public final class Determinism {

    private Determinism() {
    }

    /** SHA-256 over the joined parts, folded into a long. */
    public static long stableHash(String... parts) {
        byte[] digest = sha256(String.join("|", parts));
        long hash = 0;
        for (int i = 0; i < 8; i++) {
            hash = (hash << 8) | (digest[i] & 0xffL);
        }
        return hash;
    }

    /** Full lowercase hex SHA-256 over the joined parts. */
    public static String hashHex(String... parts) {
        byte[] digest = sha256(String.join("|", parts));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** Map a hash onto a bucket in [0, bound). */
    public static int bucket(long hash, int bound) {
        return (int) Math.floorMod(hash, bound);
    }

    /** Deterministic short identifier such as TR-1A2B3C4D5E6F. */
    public static String shortId(String prefix, int length, String... parts) {
        return prefix + "-" + hashHex(parts).substring(0, length).toUpperCase();
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
