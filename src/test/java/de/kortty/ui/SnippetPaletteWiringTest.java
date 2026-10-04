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
 * terminal tab cannot be built without a stage: the rows name the focused pane of the terminal tab
 * Send to Terminal picks (its first pane when the focused one is gone) and its number in a split tab,
 * a row sends into exactly that pane through the pane-precise send with the busy guard, the snippet
 * is looked up again before it runs, Alt+Enter opens it in the Snippet Manager after the palette
 * closed, and View &gt; Snippet Palette opens the palette with {@code $} typed.
 */
class SnippetPaletteWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path ROWS = Path.of("src/main/java/de/kortty/ui/SnippetPaletteRows.java");
    private static final Path SEND = Path.of("src/main/java/de/kortty/ui/SnippetTerminalSend.java");
    private static final Path POPUP = Path.of("src/main/java/de/kortty/ui/CommandPalettePopup.java");

    @Test
    void thePaletteListsTheSnippetsOfItsWindow() throws IOException {
        assertThat(methodBody(source(MAIN_WINDOW),
            "private void showCommandPalette(de.kortty.ui.actions.PaletteEntry.@Nullable Kind scope) {"))
            .contains("SnippetPaletteRows.source(app, this)),");
    }

    @Test
    void theRowsNameTheFocusedPaneOfTheTabSendToTerminalPicks() throws IOException {
        String target = methodBody(source(ROWS), "private static SnippetPaletteSource.Target target(");

        assertThat(target).contains("TerminalTab tab = window.snippetInsertTarget(TerminalTab.class);");
        assertThat(target).contains("SnippetTerminalSend.targetPane(panes, view.getFocusedWidget());");
        assertThat(target).contains("int number = panes.size() > 1 ? panes.indexOf(pane) + 1 : 0;");
        assertThat(target).contains("snippet -> send(app, window, tab, pane, snippet));");
        String name = methodBody(source(ROWS), "static String targetName(TerminalTab tab, SithTermFxWidget pane) {");
        assertWithMessage("a split to another server is named by its own user@host")
            .that(name).contains("tab.getTerminalView().connectionOfPane(pane)");
        assertThat(name)
            .contains("SnippetPaletteSource.targetName(tab.getEffectiveTitle(), username, host, tab.getConnectionTitle());");
    }

    @Test
    void aRowSendsIntoThePaneItNamedOnly() throws IOException {
        String send = methodBody(source(ROWS), "private static void send(");

        assertWithMessage("the snippet is read again, not taken from the palette's snapshot")
            .that(send).contains("manager.findById(snippet.getId())");
        assertThat(send).contains("new SnippetTerminalSend(manager, window::getStage, () -> { })");
        assertThat(send).contains(".sendToPane(current, () -> window, tab, pane);");

        String toPane = methodBody(source(SEND),
            "void sendToPane(Snippet snippet, Supplier<MainWindow> mainWindow, TerminalTab tab, SithTermFxWidget pane) {");
        assertThat(toPane).contains("window.holdsTab(tab) ? tab.getTerminalView() : null;");
        assertWithMessage("a busy pane is refused before the variable prompt")
            .that(toPane.indexOf("view.isPaneBusyWithInput(pane)")).isLessThan(toPane.indexOf("resolveAndPrompt(snippet)"));
        assertWithMessage("and checked again right before the send")
            .that(toPane).contains("deliverToPane(paneInput(view), pane, toSend,");
        assertThat(toPane).contains("view.focusWidget(pane);");
        String input = methodBody(source(SEND), "private static PaneInput<SithTermFxWidget> paneInput(TerminalView view) {");
        assertThat(input).contains("return view.isPaneBusyWithInput(pane);");
        assertThat(input).contains("return view.sendInputLineToPane(pane, line, generatedOneLiner);");

        String library = source(SEND);
        assertThat(methodBody(library, "void sendToTerminal(Snippet snippet, Supplier<MainWindow> mainWindow) {"))
            .contains("sendToTerminal(snippet, mainWindow, window -> window.snippetInsertTarget(TerminalTab.class));");
        String targeted = methodBody(library,
            "void sendToTerminal(Snippet snippet, Supplier<MainWindow> mainWindow, Function<MainWindow, TerminalTab> target) {");
        assertThat(targeted).contains("TerminalTab terminalTab = target.apply(window);");
        assertThat(targeted).contains("window.revealSnippetInsertTarget(terminalTab);");
        assertWithMessage("the Snippet Manager's Send to Terminal keeps writing to the tab's first pane")
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

    @Test
    void theSnippetPaletteOpensThePaletteOnItsSnippets() throws IOException {
        String window = source(MAIN_WINDOW);
        assertThat(window).contains("private final RoutedChord snippetPaletteChord = RoutedChord.unbound(\"menu.view.snippetPalette\");");
        assertThat(methodBody(window, "private void showSnippetPalette() {"))
            .contains("showCommandPalette(de.kortty.ui.actions.PaletteEntry.Kind.SNIPPET);");
        assertThat(methodBody(window, "private void toggleSnippetPalette() {")).contains("commandPalette.hide();");
        assertThat(window).contains(".consume(snippetPaletteChord::matches, SceneShortcutRouter.ALWAYS,\n"
            + "                this::toggleSnippetPalette, snippetPaletteChord::residue)");
        assertWithMessage("the snippet palette's chord gets past the open palette's key firewall")
            .that(window).contains(".or(PaletteKeys.passThrough(snippetPaletteChord::chord, isMacOs())));");
        assertThat(window).contains("snippetPalette.setOnAction(e -> Platform.runLater(this::showSnippetPalette));");

        String popup = source(POPUP);
        assertThat(methodBody(popup, "void show(Node anchor, Kind scope) {"))
            .contains("picker.show(anchor, model.scopedQuery(scope));");
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
