package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;

/**
 * Masks secrets in terminal text before it is sent to an AI profile that may forward it off this
 * computer: the terminal-selection actions (Summarize, Solve Problem, Ask), their file attachment,
 * and Ask Agent with a selection.
 *
 * <p>Two passes, in this order: the known secrets of the session (the connection's password and
 * the organisation's {@code [[rule.session-journal.replace]]} rules — the same
 * {@link SessionJournalRedactor} the capture paths use), then the well-known token formats of
 * {@link SecretTokenPatterns}. Both replace with {@link SessionJournalRedactor#REPLACEMENT}.</p>
 *
 * <p>Only an integrated model (llama.cpp, MLX) is exempt by default. An HTTP endpoint on the
 * loopback interface is not assumed to be local: a LiteLLM-style proxy or an {@code ssh -L}
 * forward on {@code localhost} hands the text straight to a cloud API. Such a profile is exempt
 * only when the user marks it as a trusted local endpoint. A local CLI provider talks to its
 * vendor's cloud and is always masked; so is a missing profile (fail closed).</p>
 */
public final class AiOutboundRedaction {

    private AiOutboundRedaction() {
    }

    /** True when text bound for this profile has to be masked first. */
    public static boolean appliesTo(AiProfile profile) {
        if (profile == null) {
            return true;
        }
        AiConnectionMode mode = profile.getConnectionMode();
        if (mode != null && mode.isEmbedded()) {
            return false;
        }
        return !isTrustedLocalEndpoint(profile);
    }

    /**
     * True when the profile's "trusted local endpoint" opt-out is set and takes effect: it counts
     * only for an HTTP profile whose API URL is on the loopback interface, so a flag left over
     * from an earlier URL never exempts a cloud endpoint.
     */
    public static boolean isTrustedLocalEndpoint(AiProfile profile) {
        return profile != null
            && profile.isTrustedLocalEndpoint()
            && canTrustLocalEndpoint(profile.getConnectionMode(), profile.getApiUrl());
    }

    /** Whether the "trusted local endpoint" option can apply to this connection mode and URL. */
    public static boolean canTrustLocalEndpoint(AiConnectionMode mode, String apiUrl) {
        return (mode == null || mode == AiConnectionMode.HTTP_API) && LocalLmModelResolver.isLoopbackHttpUrl(apiUrl);
    }

    /**
     * Masks the text for this profile, or returns it unchanged when the profile is exempt (see
     * {@link #appliesTo}).
     */
    public static RedactionResult redactFor(AiProfile profile, String text, SessionJournalRedactor knownSecrets) {
        if (!appliesTo(profile)) {
            return RedactionResult.unchanged(text);
        }
        return redact(text, knownSecrets);
    }

    /**
     * An attachment after masking, with how many secrets its content had.
     *
     * @param attachment the attachment to send; {@code null} when there was none
     * @param count      number of masked secrets
     */
    public record MaskedAttachment(AiFileAttachment attachment, int count) {
    }

    /**
     * Masks a file attachment's content for this profile. The attachment is not shown in the
     * request preview, so this is the only place its secrets are caught.
     */
    public static MaskedAttachment redactAttachmentFor(
        AiProfile profile, AiFileAttachment attachment, SessionJournalRedactor knownSecrets) {
        if (attachment == null) {
            return new MaskedAttachment(null, 0);
        }
        RedactionResult masked = redactFor(profile, attachment.content(), knownSecrets);
        if (!masked.masked()) {
            return new MaskedAttachment(attachment, 0);
        }
        return new MaskedAttachment(
            new AiFileAttachment(attachment.fileName(), attachment.sourcePath(), masked.text()), masked.count());
    }

    /**
     * Masks the session's known secrets, then the well-known token formats; the count is the sum
     * of both passes.
     *
     * @param knownSecrets the session's redactor, or {@code null} when there is no session (only
     *                     the token formats are masked then)
     */
    public static RedactionResult redact(String text, SessionJournalRedactor knownSecrets) {
        if (text == null || text.isEmpty()) {
            return RedactionResult.unchanged(text);
        }
        RedactionResult known = knownSecrets != null
            ? knownSecrets.redactCounting(text)
            : RedactionResult.unchanged(text);
        return known.then(SecretTokenPatterns.redact(known.text()));
    }
}
