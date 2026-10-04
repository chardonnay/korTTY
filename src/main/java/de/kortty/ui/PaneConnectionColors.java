package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.model.ServerConnection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Which split panes of a terminal tab run a connection whose tab color differs from the tab's, and
 * how they are marked.
 *
 * <p>A tab is colored after its own connection, and that color frames the whole tab. A pane opened
 * with <i>Split (new connection)</i>, and every same-server split of such a pane, runs a connection
 * of its own ({@link PaneOrigin}), which can be a production server inside a test server's tab or the
 * other way round. When that connection's color differs from the tab's, the pane gets a frame of its
 * own color over its edge (unless frames are switched off in the Window settings), the tab's tooltip
 * lists it, and screen readers hear its connection and color after the pane's name. A pane whose
 * connection has no color gets no frame, but is still listed and announced, so a pane inside a red
 * tab that is not a production server is never taken for one by its frame alone. Panes that run the
 * tab's own connection, and panes of another connection with the same color, are not marked.
 *
 * <p>Pure: no JavaFX. The colors come in as {@code #RRGGBB} from the caller's resolver (the
 * connection's own color, else its credential environment's; see
 * {@link ConnectionColorSupport#effectiveTabColor}), and the connection names are shown sanitized,
 * because a teamwork connection's name comes from a shared file.
 */
final class PaneConnectionColors {

    /** The longest connection name the marks show, in characters. */
    static final int MAX_CONNECTION_NAME_LENGTH = 60;

    /** Separates the panes listed in the tab's tooltip; a comma could be part of a connection's name. */
    static final String TOOLTIP_SEPARATOR = "; ";

    /**
     * How the panes of one tab are marked, as the window last applied it: the tab's color
     * ({@code #RRGGBB}, or {@code null} without one), whether colored connections get a frame
     * (Window settings), and the color of any other connection ({@code #RRGGBB} or {@code null}).
     */
    record Scheme(@Nullable String tabHex, boolean frameEnabled,
                  @Nullable Function<ServerConnection, String> colorOf) {

        Scheme {
            tabHex = ConnectionColorSupport.normalizeHex(tabHex);
        }

        /**
         * The color of {@code connection}, or {@code null} for none, for a connection the resolver
         * cannot look up or a value that is not a hex color.
         */
        @Nullable String colorOf(@Nullable ServerConnection connection) {
            if (connection == null || colorOf == null) {
                return null;
            }
            try {
                return ConnectionColorSupport.normalizeHex(colorOf.apply(connection));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * One pane of a tab: the connection it runs when that is not the tab's ({@code null} for a pane
     * that runs the tab's connection, such as the tab's first pane), and that connection's color.
     */
    record PaneInput<P>(P pane, @Nullable ServerConnection ownConnection, @Nullable String hex) {

        PaneInput {
            Objects.requireNonNull(pane, "pane");
            hex = ConnectionColorSupport.normalizeHex(hex);
        }
    }

    /**
     * A pane whose connection's color differs from its tab's: the connection's name as shown, its
     * color ({@code null} for none), and the color of the frame over the pane's edge ({@code null}
     * when it gets none: its connection has no color, or frames are switched off).
     */
    record MixedPane<P>(P pane, String connectionName, @Nullable String hex, @Nullable String frameHex) {
    }

    private PaneConnectionColors() {
    }

    /** The input for {@code pane}, with the color {@code scheme} gives its own connection. */
    static <P> PaneInput<P> input(P pane, @Nullable ServerConnection ownConnection, Scheme scheme) {
        return new PaneInput<>(pane, ownConnection,
            ownConnection != null && scheme != null ? scheme.colorOf(ownConnection) : null);
    }

    /**
     * The panes of a tab colored {@code tabHex} whose own connection has another color, in the order
     * of {@code panes}. A pane without a connection of its own runs the tab's and is never one of
     * them. Each gets a frame of its connection's color when {@code frameEnabled} and its connection
     * has a color.
     */
    static <P> List<MixedPane<P>> mixedPanes(@Nullable String tabHex, List<PaneInput<P>> panes, boolean frameEnabled) {
        String tabColor = ConnectionColorSupport.normalizeHex(tabHex);
        List<MixedPane<P>> mixed = new ArrayList<>();
        if (panes == null) {
            return mixed;
        }
        for (PaneInput<P> input : panes) {
            if (input == null || input.ownConnection() == null || Objects.equals(input.hex(), tabColor)) {
                continue;
            }
            String frame = frameEnabled ? input.hex() : null;
            mixed.add(new MixedPane<>(input.pane(), connectionName(input.ownConnection()), input.hex(), frame));
        }
        return mixed;
    }

    /**
     * How the marks name {@code connection}: its name, or {@code user@host} without one, with control
     * and bidi characters removed and capped at {@link #MAX_CONNECTION_NAME_LENGTH} characters;
     * {@code user@host} again when nothing visible is left of the name.
     */
    static String connectionName(ServerConnection connection) {
        String name = DisplayTextSanitizer.sanitize(connection.getDisplayName(), MAX_CONNECTION_NAME_LENGTH);
        if (!name.isEmpty()) {
            return name;
        }
        String endpoint = (connection.getUsername() != null ? connection.getUsername() : "") + "@"
            + (connection.getHost() != null ? connection.getHost() : "");
        return DisplayTextSanitizer.sanitize(endpoint, MAX_CONNECTION_NAME_LENGTH);
    }

    /**
     * What screen readers hear for a marked pane, after its name: "Connection db-prod, tab color red
     * (#D32F2F)", or "Connection dev-box, no tab color".
     */
    static String accessibleText(MixedPane<?> pane) {
        if (pane.hex() == null) {
            return I18n.get("terminal.pane.connectionNoColor", pane.connectionName());
        }
        return I18n.get("terminal.pane.connectionColor", pane.connectionName(), familyName(pane.hex()), pane.hex());
    }

    /**
     * The tab tooltip's line about its marked panes: "Split panes with a different color: db-prod –
     * red (#D32F2F); dev-box – no tab color", each connection and color named once; {@code null}
     * when no pane is marked.
     */
    static @Nullable String tooltipLine(List<? extends MixedPane<?>> panes) {
        if (panes == null || panes.isEmpty()) {
            return null;
        }
        Set<String> entries = new LinkedHashSet<>();
        for (MixedPane<?> pane : panes) {
            entries.add(pane.hex() == null
                ? I18n.get("tab.tooltip.mixedConnection.noColor", pane.connectionName())
                : I18n.get("tab.tooltip.mixedConnection.color", pane.connectionName(), familyName(pane.hex()),
                    pane.hex()));
        }
        return I18n.get("tab.tooltip.mixedConnections", String.join(TOOLTIP_SEPARATOR, entries));
    }

    /** The name of {@code hex}'s color family in the UI language, for example "red". */
    private static String familyName(String hex) {
        ConnectionColorSupport.Family family = ConnectionColorSupport.family(hex);
        return family != null ? I18n.get(TabColorPresentation.familyKey(family)) : hex;
    }
}
