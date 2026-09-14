package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.Test;

/** Minting, read-time validation and constant-time comparison of the bearer token. */
class ControlApiTokensTest {

    @Test
    void generateProducesFortyThreeUrlSafeCharacters() {
        String token = ControlApiTokens.generate();

        assertThat(token).hasLength(43);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void generateProducesADifferentTokenEveryCall() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            tokens.add(ControlApiTokens.generate());
        }

        assertThat(tokens).hasSize(64);
    }

    @Test
    void aGeneratedTokenValidates() throws Exception {
        ControlApiTokens.validateStored(ControlApiTokens.generate());
    }

    @Test
    void validateStoredRejectsAnEmptyToken() {
        expectThrows(ControlApiException.class, () -> ControlApiTokens.validateStored(null));
        expectThrows(ControlApiException.class, () -> ControlApiTokens.validateStored(""));
    }

    @Test
    void validateStoredRejectsAShortToken() {
        ControlApiException refusal = expectThrows(ControlApiException.class,
            () -> ControlApiTokens.validateStored("x".repeat(ControlApiProtocol.MIN_TOKEN_CHARS - 1)));

        assertThat(refusal.code()).isEqualTo(ControlErrorCode.UNAUTHORIZED);
        assertThat(refusal.getMessage()).doesNotContain("xxxx");
    }

    @Test
    void validateStoredRejectsWhitespace() {
        String padded = "y".repeat(ControlApiProtocol.MIN_TOKEN_CHARS) + " ";

        ControlApiException refusal = expectThrows(ControlApiException.class,
            () -> ControlApiTokens.validateStored(padded));

        assertThat(refusal.data()).containsEntry("reason", "malformed_token");
    }

    @Test
    void matchesAcceptsOnlyAnIdenticalToken() {
        String token = ControlApiTokens.generate();

        assertThat(ControlApiTokens.matches(token, token)).isTrue();
        assertThat(ControlApiTokens.matches(token, new String(token.toCharArray()))).isTrue();
        assertThat(ControlApiTokens.matches(token, ControlApiTokens.generate())).isFalse();
    }

    @Test
    void matchesNeverThrowsOnDifferingLengthsOrNulls() {
        String token = ControlApiTokens.generate();

        assertThat(ControlApiTokens.matches(token, "")).isFalse();
        assertThat(ControlApiTokens.matches(token, token.substring(0, 10))).isFalse();
        assertThat(ControlApiTokens.matches(token, token + "extra")).isFalse();
        assertThat(ControlApiTokens.matches(null, token)).isFalse();
        assertThat(ControlApiTokens.matches(token, null)).isFalse();
        assertThat(ControlApiTokens.matches(null, null)).isFalse();
    }

    @Test
    void anOverlongPresentedTokenIsRejectedBeforeComparison() {
        String token = ControlApiTokens.generate();

        assertThat(ControlApiTokens.matches(token, "z".repeat(ControlApiProtocol.MAX_TOKEN_CHARS + 1)))
            .isFalse();
    }
}
