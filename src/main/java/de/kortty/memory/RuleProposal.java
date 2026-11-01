package de.kortty.memory;

import java.util.Objects;

/**
 * A rule the user may save, as the chat, the agent or the user typed it. The connection fields name the
 * connection the rule was made in, when there is one; {@code topicHint} names the language or tool the
 * rule is about, when the caller knows it. Blank optional values are stored as {@code null}.
 *
 * @param text           the rule text as proposed; the user may edit it before it is saved
 * @param source         where the rule came from
 * @param connectionId   the stable id of the active connection, or {@code null}
 * @param connectionName the display name of the active connection, or {@code null}
 * @param connectionHost the host of the active connection, or {@code null}
 * @param topicHint      the language or tool the rule is about, or {@code null} to find it in the text
 */
public record RuleProposal(String text, RuleSource source, String connectionId, String connectionName,
                           String connectionHost, String topicHint) {

    public RuleProposal {
        text = text == null ? "" : text.strip();
        source = Objects.requireNonNull(source, "source");
        connectionId = blankToNull(connectionId);
        connectionName = blankToNull(connectionName);
        connectionHost = blankToNull(connectionHost);
        topicHint = blankToNull(topicHint);
    }

    /** A proposal without a connection and without a topic hint. */
    public static RuleProposal of(String text, RuleSource source) {
        return new RuleProposal(text, source, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
