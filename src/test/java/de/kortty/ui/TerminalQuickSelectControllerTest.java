package de.kortty.ui;

import de.kortty.core.TerminalLinkDetector.Kind;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * What Shift with a quick-select label opens, and how quick select is wired into the window and
 * every terminal tab. The wiring needs a JavaFX toolkit, so it is pinned in the source (CRLF-safe,
 * whitespace-insensitive) and exercised for real by {@code terminalLinksSmoke}.
 */
public class TerminalQuickSelectControllerTest {

    @Test
    public void shiftOpensWebAndMailAddressesThroughTheLinkAllowlist() {
        assertThat(TerminalQuickSelectController.openTarget(Kind.URL, "https://example.com/docs"))
            .isEqualTo(URI.create("https://example.com/docs"));
        assertThat(TerminalQuickSelectController.openTarget(Kind.EMAIL, "ops@example.com"))
            .isEqualTo(URI.create("mailto:ops@example.com"));
        // A mailto link with a field that could attach a local file is refused, and then copied.
        assertThat(TerminalQuickSelectController.openTarget(Kind.URL, "mailto:a@example.com?attach=/etc/passwd"))
            .isNull();
    }

    @Test
    public void everyOtherKindIsCopiedInsteadOfOpened() {
        for (Kind kind : new Kind[] {Kind.PATH, Kind.UUID, Kind.IPV4, Kind.IPV6, Kind.GIT_HASH, Kind.NUMBER}) {
            assertThat(TerminalQuickSelectController.openTarget(kind, "https://example.com")).isNull();
        }
        for (Kind kind : new Kind[] {Kind.URL, Kind.EMAIL, Kind.UUID, Kind.IPV4, Kind.IPV6, Kind.GIT_HASH, Kind.NUMBER}) {
            assertThat(TerminalQuickSelectController.openFile(kind, "/etc/hosts")).isNull();
        }
    }

    @Test
    public void shiftOpensAPathInTheSnippetEditorWhereThePaneOpensFiles() {
        // The pane decides (KorttyTermWidget.opens); where it does not, the path is copied.
        assertThat(TerminalQuickSelectController.openFile(Kind.PATH, "src/App.java:42"))
            .isEqualTo(TerminalFileLink.printed("src/App.java:42"));
    }

    @Test
    public void theChordIsCmdOrCtrlShiftSpaceRoutedThroughTheSceneRouterAndShownOnTheMenuItem() throws IOException {
        String mainWindow = compact("src/main/java/de/kortty/ui/MainWindow.java");

        assertThat(mainWindow).contains("privatestaticfinalKeyCombinationQUICK_SELECT_ACCELERATOR="
            + "newKeyCodeCombination(KeyCode.SPACE,KeyCombination.SHORTCUT_DOWN,KeyCombination.SHIFT_DOWN);");
        assertThat(mainWindow).contains(".consume(press->press.matches(QUICK_SELECT_ACCELERATOR),"
            + "this::isKeyboardInSelectedTerminal,this::quickSelectInCurrentTab,QUICK_SELECT_RESIDUE)");
        assertThat(mainWindow).contains("newMenuItem(I18n.get(\"menu.edit.quickSelect\"));"
            + "quickSelect.setAccelerator(QUICK_SELECT_ACCELERATOR);");
        assertThat(mainWindow).contains("booleandisableQuickSelect=!(currentTabinstanceofTerminalTab);");
    }

    @Test
    public void quickSelectsKeyFiltersAreTheSplitPanesFirst() throws IOException {
        String terminalView = compact("src/main/java/de/kortty/ui/TerminalView.java");
        String controller = compact("src/main/java/de/kortty/ui/TerminalQuickSelectController.java");

        int install = terminalView.indexOf(
            "quickSelect=TerminalQuickSelectController.install(splitPane,MainWindow.quickSelectAccelerator());");
        int viewFilter = terminalView.indexOf("splitPane.addEventFilter(KeyEvent.KEY_PRESSED,");
        assertThat(install).isAtLeast(0);
        assertThat(viewFilter).isGreaterThan(install);
        assertThat(controller).contains("splitPane.addEventFilter(KeyEvent.KEY_PRESSED,controller::onKeyPressed);");
        assertThat(controller).contains("splitPane.addEventFilter(KeyEvent.KEY_TYPED,controller::onKeyTyped);");
        assertThat(controller).contains(
            "splitPane.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,controller::onInputMethod);");
    }

    @Test
    public void copiesGoThroughKorttysClipboardAndOpensThroughTheWidgetsLinkPath() throws IOException {
        String controller = compact("src/main/java/de/kortty/ui/TerminalQuickSelectController.java");

        assertThat(controller).contains("privateConsumer<String>copier=KorttyClipboard::setText;");
        // Shift+label builds the same link a Cmd/Ctrl+click finds, and opens it only if the pane does;
        // otherwise the text is copied.
        assertThat(controller).contains("TerminalLinkResolver.Linklink=newTerminalLinkResolver.Link(HitKind.AUTO,"
            + "openTarget(target.kind(),target.text()),target.text(),hit.start(),hit.end(),"
            + "openFile(target.kind(),target.text()));");
        assertThat(controller).contains("if(!from.widget.opens(link)){copy(from,target);return;}from.widget.openLink(link);");
        assertThat(controller).doesNotContain("Desktop");
        assertThat(controller).doesNotContain("showDocument");
    }

    private static String compact(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("\\s+", "");
    }
}
