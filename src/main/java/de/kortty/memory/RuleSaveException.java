package de.kortty.memory;

import de.kortty.ui.I18n;

/**
 * A confirmed rule that was not saved, or not undone. {@link #reason()} says why, and {@link #userMessage()} is the
 * text for the user, in the language of the application. The message never shows a secret: a secret refusal names
 * only the line and the kind of secret.
 */
public final class RuleSaveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a rule was refused. Each reason has its own message key in the {@code ai.learn.refused.*} family. */
    public enum Reason {
        EMPTY("ai.learn.refused.empty"),
        SECRET("ai.learn.refused.secret"),
        TOO_LONG("ai.learn.refused.tooLong"),
        READ_ONLY("ai.learn.refused.readOnly"),
        SKILL_CREATION_NOT_ALLOWED("ai.learn.refused.skillCreation"),
        NO_TARGET("ai.learn.refused.noTarget"),
        SKILL_MISSING("ai.learn.refused.skillMissing"),
        DUPLICATE("ai.learn.refused.duplicate"),
        SKILL_EXISTS("ai.learn.refused.skillExists"),
        LIMIT("ai.learn.refused.limit"),
        CONFLICT("ai.learn.refused.conflict"),
        FAILED("ai.learn.refused.failed");

        private final String messageKey;

        Reason(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final Reason reason;
    private final Object[] arguments;

    RuleSaveException(Reason reason, Object... arguments) {
        super(reason.name());
        this.reason = reason;
        this.arguments = arguments.clone();
    }

    public Reason reason() {
        return reason;
    }

    /** The message for the user, with the arguments of the reason filled in. */
    public String userMessage() {
        return I18n.get(reason.messageKey(), arguments);
    }
}
