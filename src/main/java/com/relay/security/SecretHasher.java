package com.relay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Webhook secrets are stored only as SHA-256 hashes and compared in constant time, so a database dump or a
 * trace never reveals them.
 */
public final class SecretHasher {

    private static final java.util.regex.Pattern SHA256_HEX = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    private SecretHasher() {}

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of a presented secret against a stored hash. */
    public static boolean matches(String presentedSecret, String storedHashHex) {
        if (presentedSecret == null || storedHashHex == null || !SHA256_HEX.matcher(storedHashHex).matches()) return false;
        byte[] a = sha256Hex(presentedSecret).getBytes(StandardCharsets.UTF_8);
        byte[] b = storedHashHex.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
