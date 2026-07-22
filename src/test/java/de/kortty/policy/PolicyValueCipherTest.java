package de.kortty.policy;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class PolicyValueCipherTest {

    @Test
    void roundTripsArbitraryValues() {
        for (String plaintext : new String[] {"sk-abc123", "", "päßwörd ✓", "a".repeat(4096)}) {
            String envelope = PolicyValueCipher.encrypt(plaintext);
            assertThat(envelope).startsWith(PolicyValueCipher.PREFIX);
            assertThat(PolicyValueCipher.isEncryptedValue(envelope)).isTrue();
            assertThat(PolicyValueCipher.decrypt(envelope)).isEqualTo(plaintext);
        }
    }

    @Test
    void encryptionIsRandomizedPerCall() {
        assertThat(PolicyValueCipher.encrypt("same"))
            .isNotEqualTo(PolicyValueCipher.encrypt("same"));
    }

    @Test
    void tamperedCiphertextFailsTheGcmTagCheck() {
        String envelope = PolicyValueCipher.encrypt("secret");
        byte[] decoded = java.util.Base64.getDecoder()
            .decode(envelope.substring(PolicyValueCipher.PREFIX.length()));
        decoded[decoded.length - 1] ^= 0x01;
        String tampered = PolicyValueCipher.PREFIX
            + java.util.Base64.getEncoder().encodeToString(decoded);
        expectThrows(IllegalArgumentException.class, () -> PolicyValueCipher.decrypt(tampered));
    }

    @Test
    void rejectsForeignAndBrokenEnvelopes() {
        assertThat(PolicyValueCipher.isEncryptedValue("sk-plaintext")).isFalse();
        assertThat(PolicyValueCipher.isEncryptedValue(null)).isFalse();
        expectThrows(IllegalArgumentException.class, () -> PolicyValueCipher.decrypt("sk-plaintext"));
        expectThrows(IllegalArgumentException.class,
            () -> PolicyValueCipher.decrypt(PolicyValueCipher.PREFIX + "!!!not-base64!!!"));
        expectThrows(IllegalArgumentException.class,
            () -> PolicyValueCipher.decrypt(PolicyValueCipher.PREFIX + "AAAA"));
    }
}
