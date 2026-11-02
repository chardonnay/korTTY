package de.kortty.ui;

import de.kortty.model.ServerConnection;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/** What an AI request of one connection carries besides the prompt: the skills the connection has assigned. */
final class ConnectionAiScope {

    private ConnectionAiScope() {
    }

    /**
     * The skills {@code connection} has assigned, as a request takes them; {@code null} when it has none, so that no
     * connection-target skill is pinned. Both the request and the token preview use this.
     */
    static @Nullable Collection<String> assignedSkillIds(@Nullable ServerConnection connection) {
        Collection<String> ids = connection != null && connection.getAiSkillIds() != null
            ? connection.getAiSkillIds()
            : List.of();
        return ids.isEmpty() ? null : ids;
    }
}
