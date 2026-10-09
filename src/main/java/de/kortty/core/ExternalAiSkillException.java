package de.kortty.core;

import java.io.IOException;

/**
 * A failed call to an external AI-skill provider. {@link #reason()} lets the UI show a translated
 * message; {@link #detail()} carries what the provider said (an HTTP status, a URL, a vendor message).
 */
public class ExternalAiSkillException extends IOException {

    /** What went wrong, in the terms the AI Skills tab explains to the user. */
    public enum Reason {
        /** The query or reference is not something the provider understands. */
        INVALID_REFERENCE,
        /** Nothing at that reference — or a private repository without a token. */
        NOT_FOUND,
        /** Missing, wrong or insufficient credentials. */
        UNAUTHORIZED,
        /** The provider's request quota is used up; a token usually raises it. */
        RATE_LIMITED,
        /** The SKILL.md exceeds {@link ExternalAiSkillHttp#MAX_SKILL_CHARS}. */
        TOO_LARGE,
        /** Plain HTTP to a host other than this machine, or credentials over plain HTTP. */
        INSECURE_URL,
        /** The provider only allows this kind of search with credentials (GitHub keyword search). */
        TOKEN_REQUIRED,
        /** Any other non-2xx response. */
        HTTP_ERROR,
        /** Connection refused, DNS failure, timeout, malformed response. */
        NETWORK
    }

    private final Reason reason;
    private final String detail;

    public ExternalAiSkillException(Reason reason, String detail) {
        this(reason, detail, null);
    }

    public ExternalAiSkillException(Reason reason, String detail, Throwable cause) {
        super(reason + (detail != null && !detail.isBlank() ? ": " + detail : ""), cause);
        this.reason = reason;
        this.detail = detail != null ? detail : "";
    }

    public Reason reason() {
        return reason;
    }

    public String detail() {
        return detail;
    }
}
