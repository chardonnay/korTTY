package de.kortty.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * The control API's bearer token: minting, read-time validation and constant-time comparison.
 *
 * <p>Pure, any thread.
 *
 * <p>The token is 32 {@link SecureRandom} bytes in url-safe Base64 without padding — 43 characters —
 * regenerated on every server start, carried only in the 0600 {@code endpoint.json}, and never
 * logged, echoed in an error, passed in {@code argv} or exported in the environment. Comparison uses
 * {@link MessageDigest#isEqual(byte[], byte[])} rather than the codebase's private
 * {@code EncryptionService.constantTimeEquals}, which compares lengths first and therefore leaks
 * them.
 */
public final class ControlApiTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int TOKEN_BYTES = 32;

    private ControlApiTokens() {
    }

    /** A fresh 43-character url-safe token. */
    public static String generate() {
        byte[] random = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /**
     * Rejects a stored token that could authenticate something it should not.
     *
     * <p>Validated at read time exactly as the MLX sidecar validates its API key: an empty, short or
     * whitespace-bearing token file refuses to start the server rather than authenticating an empty
     * presented string.
     *
     * @throws ControlApiException {@link ControlErrorCode#UNAUTHORIZED} naming the defect, never the
     *     token
     */
    public static void validateStored(String token) throws ControlApiException {
        if (token == null || token.isEmpty()) {
            throw refuse("is empty");
        }
        if (token.length() < ControlApiProtocol.MIN_TOKEN_CHARS) {
            throw refuse("is shorter than " + ControlApiProtocol.MIN_TOKEN_CHARS + " characters");
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                throw refuse("contains whitespace");
            }
        }
    }

    /**
     * Constant-time comparison of the stored token against a presented one.
     *
     * <p>A presented token longer than {@link ControlApiProtocol#MAX_TOKEN_CHARS} is rejected before
     * any comparison, so a caller cannot make the server hash an unbounded string. Never throws, and
     * in particular does not throw on differing lengths.
     */
    public static boolean matches(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        if (presented.length() > ControlApiProtocol.MAX_TOKEN_CHARS) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            presented.getBytes(StandardCharsets.UTF_8));
    }

    private static ControlApiException refuse(String detail) {
        return new ControlApiException(ControlErrorCode.UNAUTHORIZED,
            "The control-API token " + detail, Map.of("reason", "malformed_token"));
    }
}
