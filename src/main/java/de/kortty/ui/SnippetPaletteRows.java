package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.KorTTYApplication;
import de.kortty.core.SnippetManager;
import de.kortty.model.ServerConnection;
import de.kortty.model.Snippet;
import de.kortty.ui.actions.SnippetPaletteSource;

import java.util.List;

/**
 * Connects the command palette's snippet rows ({@link SnippetPaletteSource}) to a main window: the
 * snippets of the application's {@link SnippetManager}, the terminal tab Send to Terminal would
 * pick ({@link MainWindow#snippetInsertTarget}), the send itself through
 * {@link SnippetTerminalSend#sendToPane}, which writes to the pane of that tab that has (or last had)
 * the keyboard focus, else to its first pane, and the Snippet Manager for Alt+Enter
 * ({@link MainWindow#showSnippetWorkspace}).
 *
 * <p>A row runs the snippet that is in the library when it runs, found again by its id, so a
 * snippet changed or deleted while the palette was open is never sent in its old form. It runs in
 * the pane it names only, and says so in the status bar; when that tab or pane closed in the
 * meantime, korTTY says there is no terminal instead of picking another one, and a pane busy with a
 * full-screen program or a paste gets nothing.
 */
final class SnippetPaletteRows {

    /** The texts these rows use, the row texts new and the name of an unnamed snippet shared. */
    static final List<String> KEYS = List.of("palette.detail.runIn", "palette.detail.runInPane",
        "palette.detail.noTerminal", "palette.snippet.noTerminal", "snippets.insertTerminal.unnamed");

    private static final SnippetPaletteSource.Texts TEXTS = new SnippetPaletteSource.Texts() {
        @Override
        public String runIn(String target) {
            return I18n.get("palette.detail.runIn", target);
        }

        @Override
        public String runInPane(String target, int pane) {
            return I18n.get("palette.detail.runInPane", target, pane);
        }

        @Override
        public String noTerminal() {
            return I18n.get("palette.detail.noTerminal");
        }

        @Override
        public String noTerminalReason() {
            return I18n.get("palette.snippet.noTerminal", CommandPalettePopup.alternateChordText());
        }

        @Override
        public String unnamed() {
            return I18n.get("snippets.insertTerminal.unnamed");
        }
    };

    private SnippetPaletteRows() {
    }

    /** The snippet rows of {@code window}'s palette. */
    static SnippetPaletteSource source(KorTTYApplication app, MainWindow window) {
        return new SnippetPaletteSource(
            new SnippetPaletteSource.Library(() -> snippets(app), snippet -> folderPath(app, snippet)),
            () -> target(app, window),
            TEXTS,
            snippet -> window.showSnippetWorkspace(snippet.getId()));
    }

    /**
     * How the rows name {@code pane} of {@code tab}: the {@code user@host} of the connection that pane
     * was opened for (a split to another server has its own, every other pane the tab's), then the
     * tab's title.
     */
    static String targetName(TerminalTab tab, SithTermFxWidget pane) {
        ServerConnection paneConnection = pane != null ? tab.getTerminalView().connectionOfPane(pane) : null;
        ServerConnection connection = paneConnection != null ? paneConnection : tab.getConnection();
        String host = connection == null || connection.isLocalShell() ? null : connection.getHost();
        String username = connection == null ? null : connection.getUsername();
        return SnippetPaletteSource.targetName(tab.getEffectiveTitle(), username, host, tab.getConnectionTitle());
    }

    private static SnippetPaletteSource.Target target(KorTTYApplication app, MainWindow window) {
        TerminalTab tab = window.snippetInsertTarget(TerminalTab.class);
        if (tab == null) {
            return null;
        }
        TerminalView view = tab.getTerminalView();
        List<SithTermFxWidget> panes = view.getOrderedWidgets();
        // Read before the palette takes the keyboard; the view remembers the pane focused last.
        SithTermFxWidget pane = SnippetTerminalSend.targetPane(panes, view.getFocusedWidget());
        if (pane == null) {
            return null;
        }
        int number = panes.size() > 1 ? panes.indexOf(pane) + 1 : 0;
        return new SnippetPaletteSource.Target(targetName(tab, pane), number,
            snippet -> send(app, window, tab, pane, snippet));
    }

    /** Send to Terminal for {@code snippet}, into {@code pane} of {@code tab} while this window still holds both. */
    private static void send(KorTTYApplication app, MainWindow window, TerminalTab tab, SithTermFxWidget pane,
                             Snippet snippet) {
        SnippetManager manager = app.getSnippetManager();
        Snippet current = manager != null ? manager.findById(snippet.getId()).orElse(null) : null;
        if (current == null) {
            return;
        }
        new SnippetTerminalSend(manager, window::getStage, () -> { })
            .sendToPane(current, () -> window, tab, pane);
    }

    private static List<Snippet> snippets(KorTTYApplication app) {
        SnippetManager manager = app.getSnippetManager();
        return manager != null ? manager.getAllSnippets() : List.of();
    }

    private static String folderPath(KorTTYApplication app, Snippet snippet) {
        SnippetManager manager = app.getSnippetManager();
        return manager != null ? manager.folderPath(snippet.getFolderId()) : "";
    }
}
