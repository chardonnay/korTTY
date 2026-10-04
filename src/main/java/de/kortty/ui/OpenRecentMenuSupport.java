package de.kortty.ui;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.RecentProjects;
import de.kortty.model.ServerConnection;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fills <i>File → Open Recent</i>, which MainWindow puts into both menu bars: under <b>Connections</b>
 * the saved connections used last ({@link de.kortty.core.RecentConnections}), each opening a tab that
 * signs in like Connect in the Connection Manager; under <b>Projects</b> the project files opened or
 * saved last and the projects of korTTY's project folder ({@link RecentProjects}), each opening like
 * <i>File → Open Project…</i>; and <b>Clear List</b>, which empties both.
 *
 * <p>The menu is rebuilt whenever the File menu opens, so its entries would go stale in the command
 * palette: MainWindow keeps the submenu out of the action harvest. Its labels are names and paths,
 * shown as they are (an underscore is no mnemonic) without control or bidi characters. Clear List
 * changes no window, so it also acts from the menu bar of a closed macOS window
 * ({@link ClosedWindowMenuRouter#noWindowNeeded}); a connection or project opens in the frontmost
 * open window, as New Tab does.
 *
 * <p>Menu items are not nodes, so this runs in a unit test without the JavaFX toolkit, except
 * {@link SeparatorMenuItem}, which holds a {@code Separator} control; the package-private builder
 * therefore takes the separator factory.
 */
final class OpenRecentMenuSupport {

    static final String MENU_KEY = "menu.file.openRecent";
    static final String CONNECTIONS_KEY = "menu.file.openRecent.connections";
    static final String PROJECTS_KEY = "menu.file.openRecent.projects";
    static final String CLEAR_KEY = "menu.file.openRecent.clear";
    static final String EMPTY_KEY = "menu.file.openRecent.empty";

    /** Every label key of the submenu. */
    static final List<String> KEYS = List.of(MENU_KEY, CONNECTIONS_KEY, PROJECTS_KEY, CLEAR_KEY, EMPTY_KEY);

    /** How many connections the menu lists; Quick Connect shows as many. */
    static final int CONNECTION_COUNT = 10;

    private static final int MAX_LABEL_LENGTH = 120;
    private static final String SEPARATOR = " — ";

    private OpenRecentMenuSupport() {
    }

    /** What the entries do. */
    interface Commands {

        /** Opens a tab for {@code connection}. */
        void connect(@NotNull ServerConnection connection);

        /** Opens the project file {@code project}. */
        void openProject(@NotNull Path project);

        /** Empties the list. */
        void clear();
    }

    /** The items of the submenu, with {@link SeparatorMenuItem}s. */
    static @NotNull List<MenuItem> items(@NotNull List<ServerConnection> connections, @NotNull List<Path> projects,
            @Nullable Path home, @NotNull Commands commands) {
        return items(connections, projects, home, commands, SeparatorMenuItem::new);
    }

    /**
     * The items of the submenu: a greyed-out <b>Connections</b> heading with the connections, the one
     * used last first, then a greyed-out <b>Projects</b> heading with the projects, then <b>Clear
     * List</b>. A heading without entries is left out; with neither, one greyed-out line says the
     * list is empty.
     *
     * @param home the home folder, shown as {@code ~} in project labels
     */
    static @NotNull List<MenuItem> items(@NotNull List<ServerConnection> connections, @NotNull List<Path> projects,
            @Nullable Path home, @NotNull Commands commands, @NotNull Supplier<? extends MenuItem> separators) {
        Objects.requireNonNull(commands, "commands");
        List<MenuItem> items = new ArrayList<>();
        if (connections.isEmpty() && projects.isEmpty()) {
            items.add(heading(EMPTY_KEY));
            return items;
        }
        if (!connections.isEmpty()) {
            items.add(heading(CONNECTIONS_KEY));
            for (ServerConnection connection : connections) {
                MenuItem item = entry(connectionLabel(connection));
                item.setOnAction(event -> commands.connect(connection));
                items.add(item);
            }
        }
        if (!projects.isEmpty()) {
            if (!items.isEmpty()) {
                items.add(separators.get());
            }
            items.add(heading(PROJECTS_KEY));
            for (Path project : projects) {
                MenuItem item = entry(RecentProjects.label(project, home));
                item.setOnAction(event -> commands.openProject(project));
                items.add(item);
            }
        }
        items.add(separators.get());
        MenuItem clear = new MenuItem(I18n.get(CLEAR_KEY));
        clear.setOnAction(event -> commands.clear());
        // It changes no window: from the menu bar of a closed macOS window it clears without opening one.
        ClosedWindowMenuRouter.noWindowNeeded(clear);
        items.add(clear);
        return items;
    }

    /**
     * A connection's label: its name and {@code user@host}, so two connections of the same name can be
     * told apart; a connection without a name shows {@code user@host} once, a local shell its name.
     */
    static @NotNull String connectionLabel(@NotNull ServerConnection connection) {
        String displayName = connection.getDisplayName();
        String name = connection.getName();
        String host = connection.getHost();
        if (connection.isLocalShell() || name == null || name.isBlank() || host == null || host.isBlank()) {
            return displayName;
        }
        String user = connection.getUsername();
        String target = user == null || user.isBlank() ? host : user + "@" + host;
        return displayName + SEPARATOR + target;
    }

    private static MenuItem heading(String key) {
        MenuItem heading = new MenuItem(I18n.get(key));
        heading.setDisable(true);
        return heading;
    }

    private static MenuItem entry(String label) {
        MenuItem item = new MenuItem(DisplayTextSanitizer.sanitize(label, MAX_LABEL_LENGTH));
        // Names and paths are shown as they are: an underscore is no mnemonic.
        item.setMnemonicParsing(false);
        return item;
    }
}
