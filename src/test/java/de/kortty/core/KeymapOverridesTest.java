package de.kortty.core;

import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeymapOverrides.Problem;
import de.kortty.core.KeymapOverrides.Rejection;
import de.kortty.core.KeymapOverrides.Resolution;
import de.kortty.core.KeymapOverrides.Rules;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The user's shortcut overrides: how they are stored, which chords korTTY refuses to take from the
 * shell, the terminal or the system, and how they are applied to the default keymap so that the
 * keymap in effect never holds a chord twice.
 */
class KeymapOverridesTest {

    /** A default keymap shaped like korTTY's: ids are the menu items' i18n keys. */
    private static final Map<String, KeyChord> DEFAULTS = defaults(
        "menu.view.commandPalette", "Shortcut+Shift+P",
        "menu.view.menuBar", "Shortcut+Shift+L",
        "menu.security.credentials", "Shortcut+Shift+M",
        "menu.file.newTab", "Shortcut+T",
        "menu.ai.agent", "Shortcut+Alt+A",
        "menu.view.panes.focusLeft", "Shortcut+Alt+Left",
        "menu.file.renameTab", null);

    private static final Rules MAC = Rules.of(Os.MAC);
    private static final Rules WINDOWS = Rules.of(Os.WINDOWS);
    private static final Rules LINUX = Rules.of(Os.LINUX);

    @Test
    void parsesTheStoredEntriesAndWritesThemBackSorted() {
        KeymapOverrides overrides = KeymapOverrides.parse(List.of(
            "menu.view.menuBar = shift+shortcut+b", "menu.view.commandPalette=Shortcut+Alt+P", "menu.file.newTab=NONE"));

        assertThat(overrides.chord("menu.view.commandPalette")).isEqualTo(KeyChord.parse("Shortcut+Alt+P"));
        assertThat(overrides.chord("menu.view.menuBar")).isEqualTo(KeyChord.parse("Shortcut+Shift+B"));
        assertThat(overrides.isUnbound("menu.file.newTab")).isTrue();
        assertThat(overrides.chord("menu.file.newTab")).isNull();
        assertThat(overrides.overrides("menu.file.newTab")).isTrue();
        assertThat(overrides.overrides("menu.ai.agent")).isFalse();
        assertThat(overrides.isUnbound("menu.ai.agent")).isFalse();
        assertThat(overrides.toEntries()).containsExactly("menu.file.newTab=none",
            "menu.view.commandPalette=Shortcut+Alt+P", "menu.view.menuBar=Shortcut+Shift+B").inOrder();
        assertThat(KeymapOverrides.parse(overrides.toEntries())).isEqualTo(overrides);
    }

    @Test
    void skipsWhatAHandEditedFileGotWrongAndTheLaterOfTwoEntriesWins() {
        KeymapOverrides overrides = KeymapOverrides.parse(Arrays.asList(
            null, "", "no separator", "=Shortcut+K", "with space=Shortcut+K", "menu.a=Shortcut+Nonsense",
            "menu.b=Shortcut+K", "menu.b=Shortcut+J", "menu.c="));

        assertThat(overrides.toEntries()).containsExactly("menu.b=Shortcut+J");
        assertThat(KeymapOverrides.parse(null).isEmpty()).isTrue();
        assertThat(KeymapOverrides.parse(List.of()).isEmpty()).isTrue();
        assertThat(KeymapOverrides.parse(List.of("garbage"))).isSameInstanceAs(KeymapOverrides.empty());
    }

    @Test
    void withAndWithoutCopy() {
        KeymapOverrides one = KeymapOverrides.empty().with("menu.file.newTab", KeyChord.parse("Shortcut+Alt+T"));
        KeymapOverrides two = one.with("menu.view.menuBar", null);

        assertThat(KeymapOverrides.empty().isEmpty()).isTrue();
        assertThat(one.toEntries()).containsExactly("menu.file.newTab=Shortcut+Alt+T");
        assertThat(two.toEntries()).containsExactly("menu.file.newTab=Shortcut+Alt+T", "menu.view.menuBar=none");
        assertThat(two.without("menu.view.menuBar")).isEqualTo(one);
        assertThat(one.without("menu.file.newTab")).isSameInstanceAs(KeymapOverrides.empty());
        assertThat(one.without("menu.unknown")).isSameInstanceAs(one);
        assertThat(two.actionIds()).containsExactly("menu.file.newTab", "menu.view.menuBar").inOrder();
        assertThrows(IllegalArgumentException.class, () -> one.with(" ", null));
    }

    @Test
    void plainCtrlWithALetterStaysWithTheShellOnWindowsAndLinux() {
        for (String letter : new String[] {"C", "D", "F", "L", "P", "R", "V", "W", "Z"}) {
            KeyChord chord = KeyChord.parse("Shortcut+" + letter);
            assertWithMessage("Ctrl+%s on Windows", letter).that(KeymapOverrides.problemOf(chord, WINDOWS))
                .isEqualTo(Problem.RESERVED_SHELL);
            assertWithMessage("Ctrl+%s on Linux", letter).that(KeymapOverrides.problemOf(chord, LINUX))
                .isEqualTo(Problem.RESERVED_SHELL);
        }
        assertWithMessage("the Control key itself, on a Mac too")
            .that(KeymapOverrides.problemOf(KeyChord.parse("Ctrl+R"), MAC)).isEqualTo(Problem.RESERVED_SHELL);
        assertWithMessage("Cmd+R is free on a Mac").that(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+R"), MAC))
            .isNull();
    }

    @Test
    void otherControlCharactersAndWordKeysStayWithTheShell() {
        for (String chord : new String[] {"Shortcut+Shift+6", "Shortcut+Shift+2", "Shortcut+Shift+Minus",
            "Shortcut+Space", "Shortcut+BracketLeft", "Shortcut+Backslash", "Shortcut+Slash", "Shortcut+Left",
            "Shortcut+Backspace", "Alt+Right", "Alt+Backspace", "Alt+B", "Shift+Alt+F", "Alt+Period"}) {
            assertWithMessage(chord).that(KeymapOverrides.problemOf(KeyChord.parse(chord), LINUX))
                .isEqualTo(Problem.RESERVED_SHELL);
        }
        assertWithMessage("Ctrl+Shift with a letter is korTTY's").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Shift+B"), LINUX)).isNull();
        assertWithMessage("Option+Left moves a word on a Mac").that(
            KeymapOverrides.problemOf(KeyChord.parse("Alt+Left"), MAC)).isEqualTo(Problem.RESERVED_SHELL);
    }

    @Test
    void theAltGrRangeIsCtrlAltOnWindowsAndOptionOnMacOs() {
        for (String chord : new String[] {"Shortcut+Alt+K", "Shortcut+Shift+Alt+Q", "Shortcut+Alt+7", "Shortcut+Alt+Plus",
            "Ctrl+Alt+E"}) {
            assertWithMessage("Windows %s", chord).that(KeymapOverrides.problemOf(KeyChord.parse(chord), WINDOWS))
                .isEqualTo(Problem.ALTGR_RANGE);
        }
        assertWithMessage("AltGr is its own key on Linux").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+K"), LINUX)).isNull();
        assertWithMessage("Cmd+Option types nothing").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+K"), MAC)).isNull();
        assertWithMessage("Option+L is @ on a German Mac").that(
            KeymapOverrides.problemOf(KeyChord.parse("Alt+L"), MAC)).isEqualTo(Problem.ALTGR_RANGE);
        assertWithMessage("Ctrl+Alt with a key that types nothing").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+Up"), WINDOWS)).isNull();
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+F5"), WINDOWS)).isNull();
    }

    @Test
    void theTerminalsOwnKeyActionsAreTaken() {
        for (String chord : new String[] {"Shortcut+C", "Shortcut+V", "Shortcut+K", "Shortcut+F", "Shortcut+Up",
            "Shortcut+Down"}) {
            assertWithMessage("macOS %s", chord).that(KeymapOverrides.problemOf(KeyChord.parse(chord), MAC))
                .isEqualTo(Problem.TERMINAL);
        }
        for (Rules rules : List.of(WINDOWS, LINUX)) {
            assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Shift+C"), rules)).isEqualTo(Problem.TERMINAL);
            assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Shift+V"), rules)).isEqualTo(Problem.TERMINAL);
            assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Up"), rules)).isEqualTo(Problem.TERMINAL);
        }
        assertWithMessage("Cmd+Shift+C is free on a Mac").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Shift+C"), MAC)).isNull();
        assertThat(KeymapOverrides.terminalChords(Os.MAC)).contains(KeyChord.parse("Shift+PageUp").physical(Os.MAC));
    }

    @Test
    void systemChordsAndUnmodifiedKeysAreRefused() {
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Tab"), MAC)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Space"), MAC)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Alt+F4"), WINDOWS)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Escape"), WINDOWS)).isEqualTo(Problem.SYSTEM);
        assertWithMessage("Mission Control and App Exposé").that(
            KeymapOverrides.problemOf(KeyChord.parse("Ctrl+Up"), MAC)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Ctrl+Down"), MAC)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Shift+Q"), MAC)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+D"), MAC)).isEqualTo(Problem.SYSTEM);
        assertWithMessage("a text console on Linux").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+F3"), LINUX)).isEqualTo(Problem.SYSTEM);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+F12"), LINUX)).isEqualTo(Problem.SYSTEM);
        assertWithMessage("but free on Windows").that(
            KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+F3"), WINDOWS)).isNull();
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+Alt+D"), LINUX)).isNull();
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("K"), MAC)).isEqualTo(Problem.NEEDS_MODIFIER);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shift+K"), WINDOWS)).isEqualTo(Problem.NEEDS_MODIFIER);
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shift+Enter"), LINUX)).isEqualTo(Problem.NEEDS_MODIFIER);
        assertWithMessage("a function key may go alone").that(
            KeymapOverrides.problemOf(KeyChord.parse("F5"), LINUX)).isNull();
        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shift+F5"), MAC)).isNull();
    }

    @Test
    void aFixedShortcutsChordIsTaken() {
        Rules rules = new Rules(Os.LINUX,
            chord -> chord.equals(KeyChord.parse("Shortcut+1")) ? "keymap.fixed.tabJump" : null, null);

        assertThat(KeymapOverrides.problemOf(KeyChord.parse("Shortcut+1"), rules)).isEqualTo(Problem.FIXED);
        Resolution resolution = KeymapOverrides.parse(List.of("menu.file.newTab=Shortcut+1")).resolve(DEFAULTS, rules);
        assertThat(resolution.rejected()).containsExactly(
            new Rejection("menu.file.newTab", KeyChord.parse("Shortcut+1"), Problem.FIXED, "keymap.fixed.tabJump"));
        assertThat(resolution.chord("menu.file.newTab")).isEqualTo(KeyChord.parse("Shortcut+T"));
    }

    @Test
    void withoutOverridesTheDefaultsAreInEffect() {
        Resolution resolution = KeymapOverrides.empty().resolve(DEFAULTS, LINUX);

        assertThat(resolution.effective()).isEqualTo(DEFAULTS);
        assertThat(resolution.rejected()).isEmpty();
        assertThat(resolution.knows("menu.file.renameTab")).isTrue();
        assertThat(resolution.chord("menu.file.renameTab")).isNull();
        assertThat(resolution.knows("menu.unknown")).isFalse();
        assertThat(new java.util.ArrayList<>(resolution.effective().keySet()))
            .containsExactlyElementsIn(DEFAULTS.keySet()).inOrder();
    }

    @Test
    void anOverrideRebindsAddsAndRemovesShortcuts() {
        Resolution resolution = KeymapOverrides.parse(List.of(
            "menu.view.commandPalette=Shortcut+Alt+P", "menu.file.renameTab=Shortcut+Shift+R",
            "menu.ai.agent=none")).resolve(DEFAULTS, LINUX);

        assertThat(resolution.rejected()).isEmpty();
        assertThat(resolution.chord("menu.view.commandPalette")).isEqualTo(KeyChord.parse("Shortcut+Alt+P"));
        assertThat(resolution.chord("menu.file.renameTab")).isEqualTo(KeyChord.parse("Shortcut+Shift+R"));
        assertThat(resolution.knows("menu.ai.agent")).isTrue();
        assertThat(resolution.chord("menu.ai.agent")).isNull();
        assertThat(resolution.chord("menu.file.newTab")).isEqualTo(KeyChord.parse("Shortcut+T"));
    }

    @Test
    void anUnknownActionIsIgnoredButKeptForTheRoundTrip() {
        KeymapOverrides overrides = KeymapOverrides.parse(List.of("menu.fromANewerVersion=Shortcut+Alt+Q"));
        Resolution resolution = overrides.resolve(DEFAULTS, LINUX);

        assertThat(resolution.effective()).isEqualTo(DEFAULTS);
        assertThat(resolution.rejected()).containsExactly(new Rejection("menu.fromANewerVersion",
            KeyChord.parse("Shortcut+Alt+Q"), Problem.UNKNOWN_ACTION, null));
        assertThat(overrides.toEntries()).containsExactly("menu.fromANewerVersion=Shortcut+Alt+Q");
    }

    @Test
    void anOverrideBackToItsOwnDefaultIsNeverRejected() {
        Resolution resolution = KeymapOverrides.parse(List.of("menu.file.newTab=Shortcut+T",
            "menu.ai.agent=Shortcut+Alt+A")).resolve(DEFAULTS, WINDOWS);

        assertWithMessage("Ctrl+T and Ctrl+Alt+A are defaults, even though a user could not choose them")
            .that(resolution.rejected()).isEmpty();
        assertThat(resolution.effective()).isEqualTo(DEFAULTS);
    }

    @Test
    void aBrokenRuleKeepsTheDefault() {
        Resolution resolution = KeymapOverrides.parse(List.of("menu.view.commandPalette=Shortcut+P",
            "menu.view.menuBar=Shortcut+Alt+L", "menu.security.credentials=Shortcut+Shift+C"))
            .resolve(DEFAULTS, WINDOWS);

        assertThat(resolution.effective()).isEqualTo(DEFAULTS);
        assertThat(resolution.rejected()).containsExactly(
            new Rejection("menu.security.credentials", KeyChord.parse("Shortcut+Shift+C"), Problem.TERMINAL, null),
            new Rejection("menu.view.commandPalette", KeyChord.parse("Shortcut+P"), Problem.RESERVED_SHELL, null),
            new Rejection("menu.view.menuBar", KeyChord.parse("Shortcut+Alt+L"), Problem.ALTGR_RANGE, null));
    }

    @Test
    void aRequiredActionCannotLoseItsShortcut() {
        Rules rules = new Rules(Os.MAC, null, Set.of("menu.view.menuBar"));
        Resolution resolution = KeymapOverrides.parse(List.of("menu.view.menuBar=none", "menu.file.newTab=none"))
            .resolve(DEFAULTS, rules);

        assertThat(resolution.chord("menu.view.menuBar")).isEqualTo(KeyChord.parse("Shortcut+Shift+L"));
        assertThat(resolution.chord("menu.file.newTab")).isNull();
        assertThat(resolution.rejected()).containsExactly(
            new Rejection("menu.view.menuBar", null, Problem.REQUIRED, null));
    }

    @Test
    void takingAnotherActionsChordIsAConflictAndKeepsTheDefault() {
        Resolution resolution = KeymapOverrides.parse(List.of("menu.view.commandPalette=Shortcut+Shift+M"))
            .resolve(DEFAULTS, LINUX);

        assertThat(resolution.chord("menu.view.commandPalette")).isEqualTo(KeyChord.parse("Shortcut+Shift+P"));
        assertThat(resolution.rejected()).containsExactly(new Rejection("menu.view.commandPalette",
            KeyChord.parse("Shortcut+Shift+M"), Problem.CONFLICT, "menu.security.credentials"));
        assertThat(KeymapOverrides.conflicts(resolution.effective(), Os.LINUX)).isEmpty();
    }

    @Test
    void aChordFreedByUnbindingCanBeTaken() {
        Resolution resolution = KeymapOverrides.parse(List.of("menu.security.credentials=none",
            "menu.view.commandPalette=Shortcut+Shift+M")).resolve(DEFAULTS, LINUX);

        assertThat(resolution.rejected()).isEmpty();
        assertThat(resolution.chord("menu.view.commandPalette")).isEqualTo(KeyChord.parse("Shortcut+Shift+M"));
        assertThat(resolution.chord("menu.security.credentials")).isNull();
    }

    @Test
    void twoActionsCanSwapTheirChords() {
        Resolution resolution = KeymapOverrides.parse(List.of("menu.view.commandPalette=Shortcut+Shift+M",
            "menu.security.credentials=Shortcut+Shift+P")).resolve(DEFAULTS, MAC);

        assertThat(resolution.rejected()).isEmpty();
        assertThat(resolution.chord("menu.view.commandPalette")).isEqualTo(KeyChord.parse("Shortcut+Shift+M"));
        assertThat(resolution.chord("menu.security.credentials")).isEqualTo(KeyChord.parse("Shortcut+Shift+P"));
    }

    @Test
    void twoOverridesOnOneChordBothGoBackAndTheCascadeEnds() {
        // The palette and the credentials both want Shift+Cmd+J; the agent wants the palette's default,
        // which is only free while the palette is rebound: all three go back to their defaults.
        Resolution resolution = KeymapOverrides.parse(List.of("menu.view.commandPalette=Shortcut+Shift+J",
            "menu.security.credentials=Shortcut+Shift+J", "menu.ai.agent=Shortcut+Shift+P")).resolve(DEFAULTS, MAC);

        assertThat(resolution.effective()).isEqualTo(DEFAULTS);
        assertThat(resolution.rejected().stream().map(Rejection::problem).distinct().toList())
            .containsExactly(Problem.CONFLICT);
        assertThat(resolution.rejected().stream().map(Rejection::actionId).toList()).containsExactly(
            "menu.view.commandPalette", "menu.security.credentials", "menu.ai.agent");
    }

    @Test
    void conflictsArePhysicalAndPlatformDependent() {
        Map<String, KeyChord> keymap = new LinkedHashMap<>();
        keymap.put("a", KeyChord.parse("Shortcut+Alt+K"));
        keymap.put("b", KeyChord.parse("Ctrl+Alt+K"));
        keymap.put("c", null);
        keymap.put("d", null);

        assertThat(KeymapOverrides.conflicts(keymap, Os.MAC)).isEmpty();
        assertThat(KeymapOverrides.conflicts(keymap, Os.LINUX)).containsExactly(
            KeyChord.parse("Ctrl+Alt+K").physical(Os.LINUX), List.of("a", "b"));
    }

    @Test
    void theEffectiveKeymapNeverHoldsAChordTwice() {
        String[] chords = {"Shortcut+Shift+P", "Shortcut+Shift+M", "Shortcut+Shift+L", "Shortcut+Shift+J",
            "Shortcut+Alt+P", "Ctrl+Alt+P", "none"};
        String[] ids = DEFAULTS.keySet().toArray(String[]::new);
        java.util.Random random = new java.util.Random(85);
        for (int round = 0; round < 500; round++) {
            List<String> entries = new java.util.ArrayList<>();
            for (String id : ids) {
                if (random.nextBoolean()) {
                    entries.add(id + "=" + chords[random.nextInt(chords.length)]);
                }
            }
            for (Rules rules : List.of(MAC, WINDOWS, LINUX)) {
                Resolution resolution = KeymapOverrides.parse(entries).resolve(DEFAULTS, rules);
                assertWithMessage("%s on %s", entries, rules.os())
                    .that(KeymapOverrides.conflicts(resolution.effective(), rules.os())).isEmpty();
                assertThat(resolution.effective().keySet()).isEqualTo(DEFAULTS.keySet());
            }
        }
    }

    private static Map<String, KeyChord> defaults(String... idAndChord) {
        Map<String, KeyChord> defaults = new LinkedHashMap<>();
        for (int i = 0; i < idAndChord.length; i += 2) {
            defaults.put(idAndChord[i], idAndChord[i + 1] == null ? null : KeyChord.parse(idAndChord[i + 1]));
        }
        return java.util.Collections.unmodifiableMap(defaults);
    }
}
