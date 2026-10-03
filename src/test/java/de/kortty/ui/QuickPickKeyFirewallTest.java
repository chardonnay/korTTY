package de.kortty.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;

/**
 * The key firewall of {@link QuickPickPopup}: a key the search field and the list leave unconsumed
 * would otherwise go on to the focus owner of the window behind the popup, often a terminal, where
 * Ctrl+D ends the remote shell and Ctrl+L clears its screen. Toolkit-free: events are built
 * directly and the firewall is called as the popup scene would call it.
 */
class QuickPickKeyFirewallTest {

    private static final Path POPUP_SOURCE = Path.of("src/main/java/de/kortty/ui/QuickPickPopup.java");
    private static final Path SNIPPET_SOURCE = Path.of("src/main/java/de/kortty/ui/SnippetQuickOpenPopup.java");

    /** The command palette's chord, the kind of key a quick pick lets through to close itself. */
    private static final KeyCombination PALETTE =
        new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    @Test
    void consumesTheKeysAShellWouldActOn() {
        List<Boolean> traversals = new ArrayList<>();
        QuickPickKeyFirewall firewall = new QuickPickKeyFirewall(QuickPickKeyFirewall.NONE, traversals::add, () -> { });

        List<KeyEvent> events = List.of(
            pressed(KeyCode.D, false, true, false, false),
            pressed(KeyCode.L, false, true, false, false),
            pressed(KeyCode.R, false, true, false, false),
            pressed(KeyCode.PAGE_UP, false, false, false, false),
            pressed(KeyCode.PAGE_DOWN, false, false, false, false),
            pressed(KeyCode.F5, false, false, false, false),
            pressed(KeyCode.D, false, false, false, true),
            typed("\u0004"),
            typed("\u000c"),
            typed("x"),
            released(KeyCode.F5));
        for (KeyEvent event : events) {
            firewall.handle(event);
        }

        for (KeyEvent event : events) {
            assertThat(event.isConsumed()).isTrue();
        }
        assertThat(traversals).isEmpty();
    }

    @Test
    void letsOnlyThePassThroughChordReachTheWindowBehind() {
        assertPassesOnlyTheChord(false, pressed(KeyCode.P, true, true, false, false));
        assertPassesOnlyTheChord(true, pressed(KeyCode.P, true, false, false, true));
    }

    private static void assertPassesOnlyTheChord(boolean macOs, KeyEvent chord) {
        Predicate<KeyEvent> passThrough = event -> event.getEventType() == KeyEvent.KEY_PRESSED
            && SceneShortcutRouter.KeyPress.of(event, macOs).matches(PALETTE);
        QuickPickKeyFirewall firewall = new QuickPickKeyFirewall(passThrough, forward -> { }, () -> { });

        List<KeyEvent> stopped = List.of(
            pressed(KeyCode.D, false, true, false, false),
            pressed(KeyCode.L, false, true, false, false),
            pressed(KeyCode.PAGE_UP, false, false, false, false),
            pressed(KeyCode.F5, false, false, false, false),
            // Plain Shortcut+P, and Ctrl+Alt+Shift+P, which is AltGr+Shift+P on Windows.
            pressed(KeyCode.P, false, !macOs, false, macOs),
            pressed(KeyCode.P, true, true, true, false),
            typed("\u0010"));
        firewall.handle(chord);
        for (KeyEvent event : stopped) {
            firewall.handle(event);
        }

        assertThat(chord.isConsumed()).isFalse();
        for (KeyEvent event : stopped) {
            assertThat(event.isConsumed()).isTrue();
        }
    }

    @Test
    void anEventSomeoneElseConsumedIsLeftAlone() {
        KeyEvent tab = pressed(KeyCode.TAB, false, false, false, false);
        tab.consume();
        List<Boolean> traversals = new ArrayList<>();

        assertThat(QuickPickKeyFirewall.consumes(tab, QuickPickKeyFirewall.NONE)).isFalse();
        new QuickPickKeyFirewall(QuickPickKeyFirewall.NONE, traversals::add, () -> { }).handle(tab);

        assertThat(traversals).isEmpty();
    }

    @Test
    void tabAndShiftTabMoveTheFocusInsideThePopup() {
        List<Boolean> traversals = new ArrayList<>();
        QuickPickKeyFirewall firewall = new QuickPickKeyFirewall(QuickPickKeyFirewall.NONE, traversals::add, () -> { });

        KeyEvent tab = pressed(KeyCode.TAB, false, false, false, false);
        KeyEvent shiftTab = pressed(KeyCode.TAB, true, false, false, false);
        KeyEvent ctrlTab = pressed(KeyCode.TAB, false, true, false, false);
        KeyEvent typedTab = typed("\t");
        firewall.handle(tab);
        firewall.handle(shiftTab);
        firewall.handle(ctrlTab);
        firewall.handle(typedTab);

        // Forward, then back; Ctrl+Tab and the typed tab character move nothing, and none reaches
        // the shell behind the popup as a completion request.
        assertThat(traversals).containsExactly(true, false).inOrder();
        assertThat(tab.isConsumed()).isTrue();
        assertThat(shiftTab.isConsumed()).isTrue();
        assertThat(ctrlTab.isConsumed()).isTrue();
        assertThat(typedTab.isConsumed()).isTrue();
    }

    @Test
    void escClosesThePopupEvenWhenNothingInsideItTookTheKey() {
        // The popup's own hide-on-Escape only sees an Esc nobody consumed, and the firewall consumes it.
        List<String> closed = new ArrayList<>();
        QuickPickKeyFirewall firewall =
            new QuickPickKeyFirewall(QuickPickKeyFirewall.NONE, forward -> { }, () -> closed.add("closed"));

        KeyEvent esc = pressed(KeyCode.ESCAPE, false, false, false, false);
        KeyEvent ctrlEsc = pressed(KeyCode.ESCAPE, false, true, false, false);
        KeyEvent escReleased = released(KeyCode.ESCAPE);
        firewall.handle(esc);
        firewall.handle(ctrlEsc);
        firewall.handle(escReleased);

        assertThat(closed).containsExactly("closed");
        assertThat(esc.isConsumed()).isTrue();
        assertThat(ctrlEsc.isConsumed()).isTrue();
        assertThat(escReleased.isConsumed()).isTrue();
    }

    @Test
    void anUnavailableRowIsReadOutAsDisabled() {
        assertThat(QuickPickPopup.accessibleText("Clear Buffer", true, "Disabled")).isEqualTo("Clear Buffer");
        assertThat(QuickPickPopup.accessibleText("Clear Buffer", false, "Disabled"))
            .isEqualTo("Clear Buffer, Disabled");
        assertThat(QuickPickPopup.accessibleText(null, false, "Disabled")).isEqualTo("Disabled");
        assertThat(QuickPickPopup.accessibleText("Clear Buffer", false, " ")).isEqualTo("Clear Buffer");
    }

    @Test
    void altEnterAsksForTheAlternateChoiceButAltGrPlainAndCmdEnterDoNot() {
        assertThat(QuickPickPopup.isAlternateChoice(pressed(KeyCode.ENTER, false, false, true, false))).isTrue();
        assertThat(QuickPickPopup.isAlternateChoice(pressed(KeyCode.ENTER, true, false, true, false))).isTrue();

        assertThat(QuickPickPopup.isAlternateChoice(pressed(KeyCode.ENTER, false, false, false, false))).isFalse();
        // AltGr arrives as Ctrl+Alt on Windows.
        assertThat(QuickPickPopup.isAlternateChoice(pressed(KeyCode.ENTER, false, true, true, false))).isFalse();
        assertThat(QuickPickPopup.isAlternateChoice(pressed(KeyCode.ENTER, false, false, true, true))).isFalse();
        assertThat(QuickPickPopup.isAlternateChoice(pressed(KeyCode.A, false, false, true, false))).isFalse();
    }

    @Test
    void enterInTheFieldAndInTheListBothKnowTheAlternateAndAPopupWithoutOneChoosesAsBefore() throws IOException {
        String popup = Files.readString(POPUP_SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String snippet = Files.readString(SNIPPET_SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(popup.split("chooseFor\\(event\\);", -1)).hasLength(3);
        assertThat(popup).contains("private Predicate<? super T> hasAlternate = item -> false;");
        assertThat(popup).contains("if (chosen != null && hasAlternate.test(chosen)) {\n"
            + "            hide();\n"
            + "            onAlternate.accept(chosen);\n"
            + "        } else {\n"
            + "            choose();");
        assertThat(snippet).doesNotContain(".alternate(");
    }

    @Test
    void theFirewallGuardsThePopupSceneAndTheSnippetQuickOpenLetsNothingThrough() throws IOException {
        String popup = Files.readString(POPUP_SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String snippet = Files.readString(SNIPPET_SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        // The scene, not the root: with no focus owner the window behind sends the keys to the scene.
        assertThat(popup).contains("popup.getScene().addEventHandler(KeyEvent.ANY,\n"
            + "            new QuickPickKeyFirewall(builder.passThrough, forward -> moveFocus(), this::hide));");
        assertThat(popup).contains("private Predicate<? super KeyEvent> passThrough = QuickPickKeyFirewall.NONE;");
        assertThat(popup).contains("field.setAccessibleText(builder.prompt);");
        assertThat(popup).contains("setAccessibleText(accessibleText(accessibleText.apply(item), available, unavailable));");
        assertThat(popup).contains("this.width = UiFontScaleSupport.scaleDimension(builder.width, true);");

        assertThat(snippet).contains("QuickPickPopup.<Snippet>builder(ROOT_ID, FIELD_ID, LIST_ID)");
        assertThat(snippet).contains(".passThrough(QuickPickKeyFirewall.NONE)");
        assertThat(snippet).doesNotContain("new Popup()");
        assertThat(snippet).contains("static final String ROOT_ID = \"snippet-quick-open\";");
        assertThat(snippet).contains("static final String FIELD_ID = \"snippet-quick-open-field\";");
        assertThat(snippet).contains("static final String LIST_ID = \"snippet-quick-open-list\";");
    }

    private static KeyEvent pressed(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, ctrl, alt, meta);
    }

    private static KeyEvent released(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", code, false, false, false, false);
    }

    private static KeyEvent typed(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }
}
