package de.kortty.core;

import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeyChord.Physical;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The general key chord of korTTY's key bindings: its stored spelling, the physical keys it presses
 * on each platform, the chord of a recorded key press and the key classes the keymap rules use.
 */
class KeyChordTest {

    @Test
    void parsesAnySpellingIntoTheCanonicalOrder() {
        assertThat(KeyChord.parse("Shortcut+Shift+P").canonical()).isEqualTo("Shortcut+Shift+P");
        assertThat(KeyChord.parse("shift - shortcut - p").canonical()).isEqualTo("Shortcut+Shift+P");
        assertThat(KeyChord.parse("alt+ctrl+shift+shortcut+k").canonical()).isEqualTo("Shortcut+Ctrl+Shift+Alt+K");
        assertThat(KeyChord.parse("  F12 ").canonical()).isEqualTo("F12");
        assertThat(KeyChord.parse("Alt+Plus").canonical()).isEqualTo("Alt+Plus");
        assertThat(KeyChord.parse("ctrl+tab").canonical()).isEqualTo("Ctrl+Tab");
    }

    @Test
    void commandKeySpellingsAreShortcutAndCtrlIsTheControlKey() {
        for (String spelling : new String[] {"Shortcut", "Mod", "CtrlCmd", "Cmd", "Command", "Meta"}) {
            assertWithMessage(spelling).that(KeyChord.parse(spelling + "+K"))
                .isEqualTo(new KeyChord(true, false, false, false, "K"));
        }
        assertThat(KeyChord.parse("Control+K")).isEqualTo(new KeyChord(false, true, false, false, "K"));
        assertThat(KeyChord.parse("Option+K")).isEqualTo(new KeyChord(false, false, false, true, "K"));
        assertThat(KeyChord.parse("Opt+K")).isEqualTo(new KeyChord(false, false, false, true, "K"));
    }

    @Test
    void inSnippetSpellingCtrlIsTheCommandKey() {
        assertThat(KeyChord.parse("Ctrl+Space", true)).isEqualTo(new KeyChord(true, false, false, false, "Space"));
        assertThat(KeyChord.parse("Cmd+Ctrl+Tab", true)).isNull();
    }

    @Test
    void acceptsKeyAliasesOfHandEditedFiles() {
        assertThat(KeyChord.parse("Shortcut+esc").key()).isEqualTo("Escape");
        assertThat(KeyChord.parse("Shortcut+return").key()).isEqualTo("Enter");
        assertThat(KeyChord.parse("Shortcut+pgdn").key()).isEqualTo("PageDown");
        assertThat(KeyChord.parse("Shortcut+uparrow").key()).isEqualTo("Up");
        assertThat(KeyChord.parse("Shortcut+bracketleft").key()).isEqualTo("BracketLeft");
    }

    @Test
    void rejectsWhatNamesNoChord() {
        assertThat(KeyChord.parse(null)).isNull();
        assertThat(KeyChord.parse("")).isNull();
        assertThat(KeyChord.parse("   ")).isNull();
        assertThat(KeyChord.parse("Shortcut")).isNull();
        assertThat(KeyChord.parse("Shortcut+Shift")).isNull();
        assertThat(KeyChord.parse("Shortcut+Shortcut+K")).isNull();
        assertThat(KeyChord.parse("Cmd+Meta+K")).isNull();
        assertThat(KeyChord.parse("Ctrl+Control+K")).isNull();
        assertThat(KeyChord.parse("Shift+Shift+K")).isNull();
        assertThat(KeyChord.parse("Alt+Option+K")).isNull();
        assertThat(KeyChord.parse("K+L")).isNull();
        assertThat(KeyChord.parse("Shortcut+Nonsense")).isNull();
        assertThat(KeyChord.parse("none")).isNull();
    }

    @Test
    void theConstructorTakesOnlyCanonicalKeyNames() {
        assertThrows(IllegalArgumentException.class, () -> new KeyChord(true, false, false, false, "k"));
        assertThrows(IllegalArgumentException.class, () -> new KeyChord(true, false, false, false, "Nonsense"));
        assertThrows(NullPointerException.class, () -> new KeyChord(true, false, false, false, null));
    }

    @Test
    void theCanonicalSpellingRoundTrips() {
        for (String raw : new String[] {"Shortcut+Shift+P", "Ctrl+Tab", "Shortcut+Ctrl+Shift+Alt+F5", "Alt+Plus",
            "F1", "Shortcut+Alt+Left", "Shortcut+Backquote", "Shift+PageUp"}) {
            KeyChord chord = KeyChord.parse(raw);
            assertWithMessage(raw).that(KeyChord.parse(chord.canonical())).isEqualTo(chord);
            assertWithMessage(raw).that(chord.toString()).isEqualTo(chord.canonical());
        }
    }

    @Test
    void shortcutIsCmdOnMacOsAndCtrlElsewhere() {
        KeyChord shortcutK = KeyChord.parse("Shortcut+K");
        KeyChord ctrlK = KeyChord.parse("Ctrl+K");

        assertThat(shortcutK.physical(Os.MAC)).isEqualTo(new Physical(false, false, false, true, "K"));
        assertThat(ctrlK.physical(Os.MAC)).isEqualTo(new Physical(true, false, false, false, "K"));
        assertThat(shortcutK.physical(Os.WINDOWS)).isEqualTo(new Physical(true, false, false, false, "K"));
        assertThat(shortcutK.physical(Os.LINUX)).isEqualTo(ctrlK.physical(Os.LINUX));

        assertWithMessage("one key on Windows and Linux").that(shortcutK.collidesWith(ctrlK, Os.WINDOWS)).isTrue();
        assertThat(shortcutK.collidesWith(ctrlK, Os.LINUX)).isTrue();
        assertWithMessage("Cmd and Control on a Mac").that(shortcutK.collidesWith(ctrlK, Os.MAC)).isFalse();
        assertThat(shortcutK.collidesWith(null, Os.MAC)).isFalse();
        assertThat(KeyChord.parse("Shortcut+Ctrl+K").physical(Os.WINDOWS)).isEqualTo(ctrlK.physical(Os.WINDOWS));
    }

    @Test
    void aRecordedKeyPressBecomesAPortableChord() {
        assertThat(KeyChord.fromKeyPress("P", true, false, false, true, Os.MAC))
            .isEqualTo(KeyChord.parse("Shortcut+Shift+P"));
        assertThat(KeyChord.fromKeyPress("TAB", false, true, false, false, Os.MAC)).isEqualTo(KeyChord.parse("Ctrl+Tab"));
        assertWithMessage("Ctrl on Windows is the command key, so the chord is Cmd on a Mac")
            .that(KeyChord.fromKeyPress("P", true, true, false, false, Os.WINDOWS))
            .isEqualTo(KeyChord.parse("Shortcut+Shift+P"));
        assertThat(KeyChord.fromKeyPress("DIGIT7", false, true, true, false, Os.LINUX))
            .isEqualTo(KeyChord.parse("Shortcut+Alt+7"));
        assertThat(KeyChord.fromKeyPress("plus", false, false, true, false, Os.LINUX)).isEqualTo(KeyChord.parse("Alt+Plus"));
    }

    @Test
    void modifiersAloneTheWindowsKeyAndUnknownKeysRecordNothing() {
        assertThat(KeyChord.fromKeyPress("CONTROL", false, true, false, false, Os.WINDOWS)).isNull();
        assertThat(KeyChord.fromKeyPress("SHIFT", true, false, false, false, Os.MAC)).isNull();
        assertThat(KeyChord.fromKeyPress("COMMAND", false, false, false, true, Os.MAC)).isNull();
        assertThat(KeyChord.fromKeyPress("K", false, false, false, true, Os.WINDOWS)).isNull();
        assertThat(KeyChord.fromKeyPress("CAPS", false, false, false, false, Os.LINUX)).isNull();
        assertThat(KeyChord.fromKeyPress(null, false, true, false, false, Os.LINUX)).isNull();
    }

    @Test
    void displayLabelsFollowThePlatform() {
        KeyChord chord = KeyChord.parse("Shortcut+Shift+P");
        assertThat(chord.displayLabel(Os.MAC)).isEqualTo("Shift+Cmd+P");
        assertThat(chord.displayLabel(Os.WINDOWS)).isEqualTo("Ctrl+Shift+P");
        assertThat(KeyChord.parse("Shortcut+Alt+Left").displayLabel(Os.MAC)).isEqualTo("Option+Cmd+Left");
        assertThat(KeyChord.parse("Shortcut+Alt+Left").displayLabel(Os.LINUX)).isEqualTo("Ctrl+Alt+Left");
        assertThat(KeyChord.parse("Ctrl+Tab").displayLabel(Os.MAC)).isEqualTo("Ctrl+Tab");
        assertWithMessage("one Ctrl where Shortcut and Ctrl are the same key")
            .that(KeyChord.parse("Shortcut+Ctrl+K").displayLabel(Os.WINDOWS)).isEqualTo("Ctrl+K");
    }

    @Test
    void classifiesTheMainKey() {
        assertThat(KeyChord.parse("Shortcut+K").isLetterKey()).isTrue();
        assertThat(KeyChord.parse("Shortcut+K").typesCharacter()).isTrue();
        assertThat(KeyChord.parse("Shortcut+7").isDigitKey()).isTrue();
        assertThat(KeyChord.parse("Shortcut+7").typesCharacter()).isTrue();
        assertThat(KeyChord.parse("Shortcut+Plus").typesCharacter()).isTrue();
        assertThat(KeyChord.parse("Shortcut+Space").typesCharacter()).isTrue();
        assertThat(KeyChord.parse("Shortcut+BracketLeft").typesCharacter()).isTrue();
        assertThat(KeyChord.parse("F12").isFunctionKey()).isTrue();
        assertThat(KeyChord.parse("F1").isFunctionKey()).isTrue();
        for (String key : new String[] {"Up", "Enter", "Tab", "Escape", "Home", "PageDown", "F5", "Delete"}) {
            assertWithMessage(key).that(KeyChord.parse("Shortcut+" + key).typesCharacter()).isFalse();
        }
        assertThat(KeyChord.parse("Shortcut+Up").isFunctionKey()).isFalse();
        assertThat(KeyChord.parse("Shortcut+F").isFunctionKey()).isFalse();
    }

    @Test
    void mapsKeysToJavaFxAndMonacoNames() {
        assertThat(KeyChord.parse("Shortcut+1").javafxKeyCodeName()).isEqualTo("DIGIT1");
        assertThat(KeyChord.parse("Alt+Plus").javafxKeyCodeName()).isEqualTo("PLUS");
        assertThat(KeyChord.parse("Shortcut+BracketLeft").javafxKeyCodeName()).isEqualTo("OPEN_BRACKET");
        assertThat(KeyChord.parse("Shortcut+Backspace").javafxKeyCodeName()).isEqualTo("BACK_SPACE");
        assertThat(KeyChord.keyForJavaFxName("back_quote")).isEqualTo("Backquote");
        assertThat(KeyChord.keyForJavaFxName("NUMPAD1")).isNull();
        assertThat(KeyChord.monacoKeyCode("Up")).isEqualTo("UpArrow");
        assertWithMessage("Monaco has no Plus key").that(KeyChord.monacoKeyCode("Plus")).isNull();
        assertThat(KeyChord.isKeyName("Equal")).isTrue();
        assertThat(KeyChord.isKeyName("equal")).isFalse();
    }

    @Test
    void theHostPlatformIsOneOfTheThree() {
        Os os = Os.current();
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assertThat(os.isMac()).isEqualTo(name.contains("mac"));
        assertThat(SnippetCompletionShortcut.isMacOs()).isEqualTo(os.isMac());
    }

    @Test
    void theSnippetShortcutKeepsItsSpellingOnTopOfTheChord() {
        assertThat(SnippetCompletionShortcut.normalize("cmd+space")).isEqualTo("Ctrl+Space");
        assertThat(SnippetCompletionShortcut.normalize("Shortcut+Shift+Tab")).isEqualTo("Ctrl+Shift+Tab");
        assertWithMessage("keys Monaco cannot bind stay out of the snippet shortcut")
            .that(SnippetCompletionShortcut.normalize("Ctrl+Plus")).isNull();
        assertThat(SnippetCompletionShortcut.fromKeyPress("PLUS", true, false, false)).isNull();
        assertThat(SnippetCompletionShortcut.fromKeyPress("EQUALS", true, false, false)).isEqualTo("Ctrl+Equal");
    }
}
