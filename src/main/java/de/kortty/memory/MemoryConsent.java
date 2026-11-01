package de.kortty.memory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Proof that the user confirmed a korTTY write to one memory file. Only code in this package can
 * issue one; the source-scan test keeps it that way. {@link MemoryStore} requires it for every
 * write that korTTY makes on its own. One confirmation covers one change: the first successful
 * write uses it up, see {@link #consume()}.
 */
public final class MemoryConsent {

    private final MemoryScope scope;
    private final Instant grantedAt;
    private final AtomicBoolean unused = new AtomicBoolean(true);

    MemoryConsent(MemoryScope scope, Instant grantedAt) {
        this.scope = scope;
        this.grantedAt = grantedAt;
    }

    /** Issues consent for {@code scope}. Called only after the user confirmed the change. */
    static MemoryConsent grant(MemoryScope scope, Instant grantedAt) {
        return new MemoryConsent(scope, grantedAt);
    }

    public MemoryScope scope() {
        return scope;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    /**
     * Uses this consent for one change. Returns {@code false} when it was used before, so a second change needs
     * a second confirmation.
     */
    public boolean consume() {
        return unused.compareAndSet(true, false);
    }
}
