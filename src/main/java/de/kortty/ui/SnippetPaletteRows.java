package de.kortty.ui;

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
 * {@link SnippetTerminalSend}, which writes to the tab's first pane, and the Snippet Manager for
 * Alt+Enter ({@link MainWindow#showSnippetWorkspace}).
 *
 * <p>A row runs the snippet that is in the library when it runs, found again by its id, so a
 * snippet changed or deleted while the palette was open is never sent in its old form. It runs in
 * the tab it names only, and says so in the status bar; when that tab closed in the meantime,
 * korTTY says there is no terminal instead of picking another one.
 */
final class SnippetPaletteRows {

    /** The texts these rows use, the row texts new and the name of an unnamed snippet shared. */
    static final List<String> KEYS = List.of("palette.detail.runIn", "palette.detail.runInFirstPane",
        "palette.detail.noTerminal", "palette.snippet.noTerminal", "snippets.insertTerminal.unnamed");

    private static final SnippetPaletteSource.Texts TEXTS = new SnippetPaletteSource.Texts() {
        @Override
        public String runIn(String target) {
            return I18n.get("palette.detail.runIn", target);
        }

        @Override
        public String runInFirstPane(String target) {
            return I18n.get("palette.detail.runInFirstPane", target);
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
     * How the rows name {@code tab}: its title and the {@code user@host} of its connection, which is
     * where its first pane runs.
     */
    static String targetName(TerminalTab tab) {
        ServerConnection connection = tab.getConnection();
        String host = connection == null || connection.isLocalShell() ? null : connection.getHost();
        String username = connection == null ? null : connection.getUsername();
        return SnippetPaletteSource.targetName(tab.getEffectiveTitle(), username, host, tab.getConnectionTitle());
    }

    private static SnippetPaletteSource.Target target(KorTTYApplication app, MainWindow window) {
        TerminalTab tab = window.snippetInsertTarget(TerminalTab.class);
        if (tab == null) {
            return null;
        }
        return new SnippetPaletteSource.Target(targetName(tab), tab.getTerminalView().getTerminalPaneCount() > 1,
            snippet -> send(app, window, tab, snippet));
    }

    /** Send to Terminal for {@code snippet}, into {@code tab} while this window still holds it. */
    private static void send(KorTTYApplication app, MainWindow window, TerminalTab tab, Snippet snippet) {
        SnippetManager manager = app.getSnippetManager();
        Snippet current = manager != null ? manager.findById(snippet.getId()).orElse(null) : null;
        if (current == null) {
            return;
        }
        new SnippetTerminalSend(manager, window::getStage, () -> { })
            .sendToTerminal(current, () -> window, owner -> owner.holdsTab(tab) ? tab : null);
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
