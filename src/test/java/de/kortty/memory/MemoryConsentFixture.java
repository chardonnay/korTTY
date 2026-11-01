package de.kortty.memory;

import java.time.Instant;

/** Test support: the consent a test takes the user to have given for a korTTY-initiated write. */
public final class MemoryConsentFixture {

    private MemoryConsentFixture() {
    }

    public static MemoryConsent granted() {
        return MemoryConsent.grant(MemoryScope.global(), Instant.now());
    }
}
