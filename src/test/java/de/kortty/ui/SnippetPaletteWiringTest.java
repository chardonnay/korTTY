package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How the command palette runs snippets, pinned against the source because MainWindow and a
 * terminal tab cannot be built without a stage: the rows name the terminal tab Send to Terminal
 * picks and whether it is split, a row sends through the Snippet Manager's own Send to Terminal into
 * exactly the tab it named (whose first pane that send writes to), the snippet is looked up again
 * before it runs, and Alt+Enter opens it in the Snippet Manager after the palette closed.
 */
class SnippetPaletteWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path ROWS = Path.of("src/main/java/de/kortty/ui/SnippetPaletteRows.java");
    private static final Path SEND = Path.of("src/main/java/de/kortty/ui/SnippetTerminalSend.java");
    private static final Path POPUP = Path.of("src/main/java/de/kortty/ui/CommandPalettePopup.java");

    @Test
    void thePaletteListsTheSnippetsOfItsWindow() throws IOException {
        assertThat(methodBody(source(MAIN_WINDOW), "private void showCommandPalette() {"))
            .contains("SnippetPaletteRows.source(app, this)),");
    }

    @Test
    void theRowsNameTheTerminalSendToTerminalPicksAndWhetherItIsSplit() throws IOException {
        String target = methodBody(source(ROWS), "private static SnippetPaletteSource.Target target(");

        assertThat(target).contains("TerminalTab tab = window.snippetInsertTarget(TerminalTab.class);");
        assertThat(target).contains("tab.getTerminalView().getTerminalPaneCount() > 1");
        assertThat(methodBody(source(ROWS), "static String targetName(TerminalTab tab) {"))
            .contains("SnippetPaletteSource.targetName(tab.getEffectiveTitle(), username, host, tab.getConnectionTitle());");
    }

    @Test
    void aRowSendsLikeSendToTerminalIntoTheTabItNamedOnly() throws IOException {
        String send = methodBody(source(ROWS), "private static void send(");

        assertWithMessage("the snippet is read again, not taken from the palette's snapshot")
            .that(send).contains("manager.findById(snippet.getId())");
        assertThat(send).contains("new SnippetTerminalSend(manager, window::getStage, () -> { })");
        assertThat(send).contains(".sendToTerminal(current, () -> window, owner -> owner.holdsTab(tab) ? tab : null);");

        String library = source(SEND);
        assertThat(methodBody(library, "void sendToTerminal(Snippet snippet, Supplier<MainWindow> mainWindow) {"))
            .contains("sendToTerminal(snippet, mainWindow, window -> window.snippetInsertTarget(TerminalTab.class));");
        String targeted = methodBody(library,
            "void sendToTerminal(Snippet snippet, Supplier<MainWindow> mainWindow, Function<MainWindow, TerminalTab> target) {");
        assertThat(targeted).contains("TerminalTab terminalTab = target.apply(window);");
        assertThat(targeted).contains("window.revealSnippetInsertTarget(terminalTab);");
        assertWithMessage("Send to Terminal writes to the tab's first pane, the one the rows name")
            .that(methodBody(library, "private static void sendPayload("))
            .contains("terminalTab.getTerminalView().sendInputLine(payload);");
    }

    @Test
    void altEnterOpensTheSnippetInTheSnippetManagerAfterThePaletteClosed() throws IOException {
        assertThat(methodBody(source(ROWS), "static SnippetPaletteSource source(KorTTYApplication app, MainWindow window) {"))
            .contains("snippet -> window.showSnippetWorkspace(snippet.getId()));");

        String popup = source(POPUP);
        assertThat(popup).contains(".alternate(entry -> entry.alternate() != null, this::runAlternate)");
        assertThat(methodBody(popup, "private void runAlternate(PaletteEntry entry) {"))
            .contains("Platform.runLater(entry.alternate());");
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
