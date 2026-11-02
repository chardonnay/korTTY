package de.kortty.memory;

import de.kortty.core.AiSecretGuard;

/** The text contains a secret. Passwords and tokens never go into memory. */
public final class MemorySecretException extends MemoryWriteException {

    private final AiSecretGuard.Finding finding;

    public MemorySecretException(AiSecretGuard.Finding finding) {
        super("The memory text contains a possible secret (" + finding.category() + ", line " + finding.line()
            + "): " + finding.maskedExcerpt());
        this.finding = finding;
    }

    public AiSecretGuard.Finding finding() {
        return finding;
    }
}
