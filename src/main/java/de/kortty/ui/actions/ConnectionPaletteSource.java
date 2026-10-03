package de.kortty.ui.actions;

import de.kortty.model.ServerConnection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The command palette's rows for connections ({@code @}): first the saved connections, the most
 * recently used first and the others by name, then — only while the organization's policy allows
 * teamwork — the connections shared with this computer through teamwork, by name, without the ones
 * deleted here (the teamwork recycle bin). Choosing a row opens a tab for the connection. FX-free:
 * the window supplies the connections, the policy check, the texts and the way to connect.
 *
 * <p>A row is titled with the connection's name and its detail says where it connects:
 * {@code user@host} (for a local shell the words "Local Shell") and the connection's group. The
 * detail is built from the user name, the host and the group only, never from a password, a
 * credential, an SSH key or a temporary key. A teamwork row starts its detail with a badge and
 * always names {@code user@host}, because its name comes from a shared file that someone else
 * writes. A connection whose target or jump host the server policy blocks is listed but cannot be
 * chosen, and says why.
 *
 * <p>A saved connection is keyed {@code conn:<id>} and a teamwork connection
 * {@code conn:teamwork:<id>}, so the two never take each other's place among the recent choices.
 * Folder placeholders and connections without an id are left out.
 */
public final class ConnectionPaletteSource implements PaletteSource {

    /** Prefix of a saved connection's key. */
    public static final String KEY_PREFIX = "conn:";
    /** Prefix of a teamwork connection's key. */
    public static final String TEAMWORK_KEY_PREFIX = KEY_PREFIX + "teamwork:";

    /** The saved connections first, the most recently used first, the rest by name. */
    private static final Comparator<ServerConnection> BY_LAST_USE =
        Comparator.comparingLong(ServerConnection::getLastUsed).reversed()
            .thenComparing(ConnectionPaletteSource::name, String.CASE_INSENSITIVE_ORDER);
    /** Teamwork connections by name only: their usage data comes from a shared file. */
    private static final Comparator<ServerConnection> BY_NAME =
        Comparator.comparing(ConnectionPaletteSource::name, String.CASE_INSENSITIVE_ORDER);

    /**
     * Where the connections come from.
     *
     * @param saved              the connections saved on this computer
     * @param teamworkAllowed    whether the organization's policy allows teamwork; without it the
     *                           teamwork connections are not even asked for
     * @param teamwork           the connections of every teamwork source
     * @param deletedTeamworkIds the ids of the teamwork connections deleted on this computer
     */
    public record Connections(Supplier<List<ServerConnection>> saved, BooleanSupplier teamworkAllowed,
                              Supplier<List<ServerConnection>> teamwork, Supplier<Set<String>> deletedTeamworkIds) {
        public Connections {
            Objects.requireNonNull(saved, "saved");
            Objects.requireNonNull(teamworkAllowed, "teamworkAllowed");
            Objects.requireNonNull(teamwork, "teamwork");
            Objects.requireNonNull(deletedTeamworkIds, "deletedTeamworkIds");
        }
    }

    /** The texts of the rows, in the UI language. */
    public interface Texts {
        /** The badge in front of a teamwork connection's detail. */
        String teamwork();

        /** What a local shell's detail names instead of {@code user@host}. */
        String localShell();

        /** Why a connection to {@code target} ({@code host:port}) cannot be opened. */
        String blocked(String target);
    }

    private final Connections connections;
    private final Function<ServerConnection, Optional<String>> blockedTarget;
    private final Texts texts;
    private final Consumer<ServerConnection> connect;

    /**
     * @param blockedTarget the first target of a connection that the server policy blocks, as
     *                      {@code host:port}, or empty
     * @param connect       opens a tab for a connection, signing in as the Connection Manager does
     */
    public ConnectionPaletteSource(Connections connections, Function<ServerConnection, Optional<String>> blockedTarget,
                                   Texts texts, Consumer<ServerConnection> connect) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.blockedTarget = Objects.requireNonNull(blockedTarget, "blockedTarget");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.connect = Objects.requireNonNull(connect, "connect");
    }

    @Override
    public PaletteEntry.Kind kind() {
        return PaletteEntry.Kind.CONNECTION;
    }

    @Override
    public List<PaletteEntry> entries() {
        List<PaletteEntry> entries = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ServerConnection connection : listed(connections.saved().get(), Set.of(), BY_LAST_USE)) {
            add(entries, keys, connection, false);
        }
        if (connections.teamworkAllowed().getAsBoolean()) {
            Set<String> deleted = connections.deletedTeamworkIds().get();
            for (ServerConnection connection : listed(connections.teamwork().get(),
                    deleted != null ? deleted : Set.of(), BY_NAME)) {
                add(entries, keys, connection, true);
            }
        }
        return entries;
    }

    /** The connections that get a row, in their order. */
    private static List<ServerConnection> listed(List<ServerConnection> connections, Set<String> deletedIds,
                                                 Comparator<ServerConnection> order) {
        if (connections == null) {
            return List.of();
        }
        List<ServerConnection> listed = new ArrayList<>();
        for (ServerConnection connection : connections) {
            if (connection != null && connection.getId() != null && !connection.getId().isBlank()
                    && !connection.isPlaceholder() && !deletedIds.contains(connection.getId())) {
                listed.add(connection);
            }
        }
        listed.sort(order);
        return listed;
    }

    private void add(List<PaletteEntry> entries, Set<String> keys, ServerConnection connection, boolean teamwork) {
        String key = (teamwork ? TEAMWORK_KEY_PREFIX : KEY_PREFIX) + connection.getId();
        if (!keys.add(key)) {
            return;
        }
        String title = name(connection);
        Optional<String> blocked = blockedTarget.apply(connection);
        String detail = teamwork
            // The name comes from someone else's file: always say where the row really connects.
            ? TabPaletteSource.join(texts.teamwork(), detail(connection, null))
            : detail(connection, title);
        entries.add(new PaletteEntry(PaletteEntry.Kind.CONNECTION, key, title, detail, "",
            blocked.isEmpty(), blocked.map(texts::blocked).orElse(""), false,
            () -> connect.accept(connection)));
    }

    /**
     * {@code user@host} (or "Local Shell") and the group; {@code user@host} is left out when
     * {@code title} already is exactly that.
     */
    private String detail(ServerConnection connection, String title) {
        boolean localShell = connection.isLocalShell();
        return TabPaletteSource.connectionDetail(title, connection.getUsername(),
            localShell ? null : connection.getHost(), localShell ? texts.localShell() : "", connection.getGroup());
    }

    private static String name(ServerConnection connection) {
        String name = connection.getDisplayName();
        return name != null ? name : "";
    }
}
