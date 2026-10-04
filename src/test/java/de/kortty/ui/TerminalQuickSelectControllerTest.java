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
        // The router reads the chord in effect: the default above, or the user's rebinding of it.
        assertThat(mainWindow).contains("privatefinalRoutedChordquickSelectChord="
            + "newRoutedChord(\"menu.edit.quickSelect\",QUICK_SELECT_ACCELERATOR,QUICK_SELECT_RESIDUE);");
        assertThat(mainWindow).contains(".consume(quickSelectChord::matches,"
            + "this::isKeyboardInSelectedTerminal,this::quickSelectInCurrentTab,quickSelectChord::residue)");
        assertThat(mainWindow).contains("=menuItem(\"menu.edit.quickSelect\");"
            + "quickSelect.setAccelerator(QUICK_SELECT_ACCELERATOR);");
        assertThat(mainWindow).contains("booleandisableQuickSelect=!(currentTabinstanceofTerminalTab);");
    }

    @Test
    public void quickSelectsKeyFiltersAreTheSplitPanesFirst() throws IOException {
        String terminalView = compact("src/main/java/de/kortty/ui/TerminalView.java");
        String controller = compact("src/main/java/de/kortty/ui/TerminalQuickSelectController.java");

        int install = terminalView.indexOf(
            "quickSelect=TerminalQuickSelectController.install(splitPane,MainWindow::effectiveQuickSelectAccelerator,"
                + "()->quickSelectSettings(TerminalView::readGlobalSettings));");
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

    @Test
    public void startingClearsTheLinkHoverSoNoTooltipCoversTheLabels() throws IOException {
        String controller = compact("src/main/java/de/kortty/ui/TerminalQuickSelectController.java");

        int focus = controller.indexOf("splitPane.focusWidget(widget);");
        int clear = controller.indexOf("panel.linkHover().clear();");
        int capture = controller.indexOf("QuickSelectScreen.capture(");
        assertThat(focus).isAtLeast(0);
        // Before the labels are drawn: no key reaches the canvas's own hover teardown while quick select runs.
        assertThat(clear).isGreaterThan(focus);
        assertThat(clear).isLessThan(capture);
    }

    @Test
    public void eachQuickSelectUsesTheLettersAndPatternsInEffectWhenItStarts() throws IOException {
        String controller = compact("src/main/java/de/kortty/ui/TerminalQuickSelectController.java");

        // Read on every start, so a change in the settings applies to open tabs at once.
        int read = controller.indexOf("QuickSelectSettingsoptions=currentSettings();");
        int capture = controller.indexOf("QuickSelectScreen.capture(panel.getTerminalTextBuffer(),geometry.scrollOrigin(),"
            + "geometry.rows(),options.patterns());");
        int session = controller.indexOf("QuickSelectSession.of(hits,options.alphabet(),trigger.get());");
        assertThat(read).isAtLeast(0);
        assertThat(capture).isGreaterThan(read);
        assertThat(session).isGreaterThan(capture);
        assertThat(controller).doesNotContain("QuickSelectLabels.DEFAULT_ALPHABET,trigger");
    }

    @Test
    public void theTerminalViewReadsTheSettingsAndFallsBackToTheDefaults() {
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setTerminalQuickSelectAlphabet("hjkl");
        settings.setTerminalQuickSelectPatterns(java.util.List.of("INC\\d+"));

        de.kortty.core.QuickSelectSettings read = TerminalView.quickSelectSettings(() -> settings);

        assertThat(read.alphabet()).isEqualTo("hjkl");
        assertThat(read.patterns().size()).isEqualTo(1);
        assertThat(TerminalView.quickSelectSettings(() -> null)).isEqualTo(de.kortty.core.QuickSelectSettings.DEFAULTS);
        assertThat(TerminalView.quickSelectSettings(() -> {
            throw new IllegalStateException("no application");
        })).isEqualTo(de.kortty.core.QuickSelectSettings.DEFAULTS);
    }

    @Test
    public void theSettingsPageStoresOnlyLettersAndPatternsQuickSelectCanUse() throws IOException {
        String settings = compact("src/main/java/de/kortty/ui/SettingsDialog.java");

        // Save stays in the dialog and shows the Terminal page while a field has a problem.
        int saveFilter = settings.indexOf("}elseif(!QuickSelectSettingsSupport.canSave(quickSelectAlphabetField.getText(),"
            + "quickSelectPatternsArea.getText())){");
        assertThat(saveFilter).isAtLeast(0);
        assertThat(settings.indexOf("event.consume();revealQuickSelectProblems();}", saveFilter)).isGreaterThan(saveFilter);
        assertThat(settings).contains("privatebooleanapplySettings(){");
        int guard = settings.indexOf("if(!QuickSelectSettingsSupport.canSave(quickSelectAlphabetField.getText(),"
            + "quickSelectPatternsArea.getText())){revealQuickSelectProblems();returnfalse;}");
        int firstSetter = settings.indexOf("settings.setFontFamily(fontFamilyCombo.getValue());");
        assertThat(guard).isGreaterThan(settings.indexOf("privatebooleanapplySettings(){"));
        assertThat(guard).isLessThan(firstSetter);
        assertThat(settings).contains("globalSettings.setTerminalQuickSelectAlphabet("
            + "QuickSelectSettingsSupport.alphabet(quickSelectAlphabetField.getText()));");
        assertThat(settings).contains("globalSettings.setTerminalQuickSelectPatterns("
            + "QuickSelectSettingsSupport.patterns(quickSelectPatternsArea.getText()));");
        // The fields sit in the Links section, after its checkbox.
        assertThat(settings.indexOf("terminalGrid.add(newVBox(4,quickSelectPatternsArea,quickSelectPatternsError),1,terminalRow++);"))
            .isGreaterThan(settings.indexOf("terminalGrid.add(terminalLinkDetectionCheck,0,terminalRow++,2,1);"));
    }

    private static String compact(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("\\s+", "");
    }
}
