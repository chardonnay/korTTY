package de.kortty.core;

import de.kortty.core.SnippetCompletionShortcut.Binding;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetCompletionShortcutTest {

    @Test
    void normalizeOrdersModifiersAndKeepsTheDefaultSpelling() {
        assertThat(SnippetCompletionShortcut.normalize("Shift+Tab")).isEqualTo("Shift+Tab");
        assertThat(SnippetCompletionShortcut.normalize("alt - shift - tab")).isEqualTo("Shift+Alt+Tab");
        assertThat(SnippetCompletionShortcut.normalize("cmd+space")).isEqualTo("Ctrl+Space");
        assertThat(SnippetCompletionShortcut.normalize("CTRL+ALT+k")).isEqualTo("Ctrl+Alt+K");
        assertThat(SnippetCompletionShortcut.DEFAULT).isEqualTo("Shift+Tab");
    }

    @Test
    void normalizeAcceptsAKeyWithoutAnyModifier() {
        assertThat(SnippetCompletionShortcut.normalize("F2")).isEqualTo("F2");
        assertThat(SnippetCompletionShortcut.normalize("insert")).isEqualTo("Insert");
    }

    @Test
    void normalizeRejectsModifiersAlonePlusRepeatedOrMissingKeys() {
        assertThat(SnippetCompletionShortcut.normalize("Ctrl")).isNull();
        assertThat(SnippetCompletionShortcut.normalize("Ctrl+Shift")).isNull();
        assertThat(SnippetCompletionShortcut.normalize("Ctrl+Ctrl+Tab")).isNull();
        assertThat(SnippetCompletionShortcut.normalize("Tab+Space")).isNull();
        assertThat(SnippetCompletionShortcut.normalize("Ctrl+Nonsense")).isNull();
        assertThat(SnippetCompletionShortcut.normalize("")).isNull();
        assertThat(SnippetCompletionShortcut.normalize(null)).isNull();
        assertThat(SnippetCompletionShortcut.isValid("Ctrl+Shift+Space")).isTrue();
        assertThat(SnippetCompletionShortcut.isValid("Ctrl+Shift")).isFalse();
    }

    @Test
    void normalizeOrDefaultFallsBackForAnUnusableStoredValue() {
        assertThat(SnippetCompletionShortcut.normalizeOrDefault("Ctrl+Space")).isEqualTo("Ctrl+Space");
        assertThat(SnippetCompletionShortcut.normalizeOrDefault("garbage")).isEqualTo(SnippetCompletionShortcut.DEFAULT);
        assertThat(SnippetCompletionShortcut.normalizeOrDefault(null)).isEqualTo(SnippetCompletionShortcut.DEFAULT);
    }

    @Test
    void bindingCarriesTheModifierFlagsAndTheMonacoKeyCodeName() {
        Binding shiftTab = SnippetCompletionShortcut.binding("Shift+Tab");
        assertThat(shiftTab).isEqualTo(new Binding(false, true, false, "Tab"));

        // Letters, digits and arrows use Monaco's own KeyCode member names, which the page resolves
        // through monaco.KeyCode[name] — no key table is duplicated in JavaScript.
        assertThat(SnippetCompletionShortcut.binding("Ctrl+Alt+K"))
            .isEqualTo(new Binding(true, false, true, "KeyK"));
        assertThat(SnippetCompletionShortcut.binding("Ctrl+7")).isEqualTo(new Binding(true, false, false, "Digit7"));
        assertThat(SnippetCompletionShortcut.binding("Alt+Up")).isEqualTo(new Binding(false, false, true, "UpArrow"));
        assertThat(SnippetCompletionShortcut.binding("Ctrl+Shift+Space"))
            .isEqualTo(new Binding(true, true, false, "Space"));
        assertThat(SnippetCompletionShortcut.binding("F9")).isEqualTo(new Binding(false, false, false, "F9"));
        assertThat(SnippetCompletionShortcut.binding("BracketRight"))
            .isEqualTo(new Binding(false, false, false, "BracketRight"));
        assertThat(SnippetCompletionShortcut.binding("Ctrl+Shift")).isNull();
    }

    @Test
    void fromKeyPressRecordsTheChordAndIgnoresAModifierOnItsOwn() {
        assertThat(SnippetCompletionShortcut.fromKeyPress("TAB", false, true, false)).isEqualTo("Shift+Tab");
        assertThat(SnippetCompletionShortcut.fromKeyPress("SPACE", true, false, false)).isEqualTo("Ctrl+Space");
        assertThat(SnippetCompletionShortcut.fromKeyPress("K", true, false, true)).isEqualTo("Ctrl+Alt+K");
        assertThat(SnippetCompletionShortcut.fromKeyPress("DIGIT3", true, true, false)).isEqualTo("Ctrl+Shift+3");
        assertThat(SnippetCompletionShortcut.fromKeyPress("OPEN_BRACKET", true, false, false))
            .isEqualTo("Ctrl+BracketLeft");
        assertThat(SnippetCompletionShortcut.fromKeyPress("F5", false, false, false)).isEqualTo("F5");

        // Holding a modifier must not record a chord of its own, and an unmapped key is refused.
        assertThat(SnippetCompletionShortcut.fromKeyPress("CONTROL", true, false, false)).isNull();
        assertThat(SnippetCompletionShortcut.fromKeyPress("SHIFT", false, true, false)).isNull();
        assertThat(SnippetCompletionShortcut.fromKeyPress("CAPS", false, false, false)).isNull();
        assertThat(SnippetCompletionShortcut.fromKeyPress(null, false, true, false)).isNull();
    }

    @Test
    void displayLabelShowsTheCtrlCmdModifierAsCmdOnMac() {
        assertThat(SnippetCompletionShortcut.displayLabel("Ctrl+Space", false)).isEqualTo("Ctrl+Space");
        assertThat(SnippetCompletionShortcut.displayLabel("Ctrl+Space", true)).isEqualTo("Cmd+Space");
        assertThat(SnippetCompletionShortcut.displayLabel("Shift+Tab", true)).isEqualTo("Shift+Tab");
        assertThat(SnippetCompletionShortcut.displayLabel("garbage", false)).isEqualTo(SnippetCompletionShortcut.DEFAULT);
    }

    @Test
    void everyRecordableKeyResolvesToAMonacoKeyCode() {
        // The recorder may only offer chords the page can register: every JavaFX key it maps must
        // come back from binding() with a Monaco KeyCode name.
        for (String javafxKey : new String[] {"TAB", "SPACE", "ENTER", "ESCAPE", "BACK_SPACE", "DELETE", "INSERT",
            "HOME", "END", "PAGE_UP", "PAGE_DOWN", "UP", "DOWN", "LEFT", "RIGHT", "A", "Z", "DIGIT0", "DIGIT9",
            "F1", "F12", "COMMA", "PERIOD", "SLASH", "BACK_SLASH", "SEMICOLON", "QUOTE", "OPEN_BRACKET",
            "CLOSE_BRACKET", "MINUS", "EQUALS", "BACK_QUOTE"}) {
            String chord = SnippetCompletionShortcut.fromKeyPress(javafxKey, true, false, false);
            assertThat(chord).isNotNull();
            Binding binding = SnippetCompletionShortcut.binding(chord);
            assertThat(binding).isNotNull();
            assertThat(binding.monacoKeyCode()).isNotEmpty();
        }
    }
}
