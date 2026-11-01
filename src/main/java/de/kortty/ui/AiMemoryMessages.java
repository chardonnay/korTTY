package de.kortty.ui;

import de.kortty.core.AiSecretGuard;
import de.kortty.memory.MemoryCleanupService;
import de.kortty.memory.MemoryConflictException;
import de.kortty.memory.MemoryInvariantException;
import de.kortty.memory.MemoryLimitException;
import de.kortty.memory.MemoryReadOnlyException;
import de.kortty.memory.MemoryRowEdit;
import de.kortty.memory.MemorySecretException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The texts the Memory tab shows for a memory action that failed or was refused. A secret is never shown: a refusal names
 * the line and the kind of secret only.
 */
final class AiMemoryMessages {

    private AiMemoryMessages() {
    }

    /** Whether the failure means that the file changed outside korTTY since it was loaded. */
    static boolean isConflict(Throwable failure) {
        return unwrap(failure) instanceof MemoryConflictException;
    }

    /** The localized message for a failed memory action. */
    static String describe(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof MemorySecretException secret) {
            return I18n.get("settings.ai.memory.secret.refused", secret.finding().line(),
                categoryLabel(secret.finding().category()));
        }
        if (cause instanceof MemoryConflictException) {
            return I18n.get("settings.ai.memory.conflict");
        }
        if (cause instanceof MemoryReadOnlyException) {
            return I18n.get("settings.ai.memory.readOnly");
        }
        if (cause instanceof MemoryLimitException) {
            return I18n.get("settings.ai.memory.limit");
        }
        if (cause instanceof MemoryRowEdit.RowNotFoundException) {
            return I18n.get("settings.ai.memory.rowMoved");
        }
        if (cause instanceof MemoryCleanupService.CleanupException cleanup) {
            return cleanupProblem(cleanup.problem());
        }
        if (cause instanceof AiMemoryAssistant.NoProfileException) {
            return I18n.get("settings.ai.memory.cleanup.noProfile");
        }
        if (cause instanceof MemoryInvariantException) {
            return I18n.get("settings.ai.memory.invariant");
        }
        String detail = cause.getMessage() != null && !cause.getMessage().isBlank()
            ? cause.getMessage()
            : cause.getClass().getSimpleName();
        return I18n.get("settings.ai.memory.error", detail);
    }

    static String categoryLabel(AiSecretGuard.Category category) {
        return switch (category) {
            case TOKEN_FORMAT -> I18n.get("settings.aiSkills.secret.category.tokenFormat");
            case SECRET_ASSIGNMENT -> I18n.get("settings.aiSkills.secret.category.assignment");
            case PROSE_SECRET -> I18n.get("settings.aiSkills.secret.category.prose");
            case HIGH_ENTROPY -> I18n.get("settings.aiSkills.secret.category.highEntropy");
            case VAULT_VALUE -> I18n.get("settings.aiSkills.secret.category.vault");
        };
    }

    private static String cleanupProblem(MemoryCleanupService.Problem problem) {
        return switch (problem) {
            case NOTHING_TO_MERGE -> I18n.get("settings.ai.memory.cleanup.nothing");
            case NOTHING_TO_SHORTEN -> I18n.get("settings.ai.memory.cleanup.nothingShorten");
            case NO_REPLY, MALFORMED_REPLY -> I18n.get("settings.ai.memory.cleanup.invalid");
            case COUNT_MISMATCH -> I18n.get("settings.ai.memory.cleanup.countMismatch");
            case NOTHING_TO_CHANGE -> I18n.get("settings.ai.memory.cleanup.noChange");
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
