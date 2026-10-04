package de.kortty.ui;

import de.kortty.core.KeyChord;
import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeymapOverrides;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every menu item of the main window's menu bar is installed on the same scene (and again on the
 * macOS system menu bar), so two items sharing an accelerator means the later one never fires.
 * Regression guard: "Create Backup..." (File) and "File Browser > Show on Left" (View) both used
 * Shortcut+Shift+B, which silently killed the file-browser left-dock shortcut.
 *
 * <p>The menu bar cannot be built without a live {@code App} and JavaFX toolkit, so this test reads
 * the accelerator declarations straight out of the MainWindow source instead.
 */
class MainWindowAcceleratorUniquenessTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    /** {@code private static final KeyCombination NAME = new KeyCodeCombination(args);} */
    private static final Pattern CONSTANT = Pattern.compile(
        "static final KeyCombination (\\w+)\\s*=\\s*new KeyCodeCombination\\(([^)]*)\\)");

    /** {@code item.setAccelerator(new KeyCodeCombination(args));} or {@code item.setAccelerator(NAME);} */
    private static final Pattern USAGE = Pattern.compile(
        "\\.setAccelerator\\(\\s*(?:new KeyCodeCombination\\(([^)]*)\\)|(\\w+))\\s*\\)");

    /** {@code KeyCode.DIGIT1} to {@code DIGIT9} and {@code KeyCode.NUMPAD1} to {@code NUMPAD9}. */
    private static final Pattern JUMP_DIGIT = Pattern.compile("\\b(?:DIGIT|NUMPAD)[1-9]\\b");

    @Test
    void noTwoMenuItemsShareAnAccelerator() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        Map<String, String> constants = new LinkedHashMap<>();
        Matcher constantMatcher = CONSTANT.matcher(source);
        while (constantMatcher.find()) {
            constants.put(constantMatcher.group(1), normalize(constantMatcher.group(2)));
        }
        assertThat(constants).isNotEmpty();

        Map<String, List<String>> byCombination = new LinkedHashMap<>();
        Matcher usageMatcher = USAGE.matcher(source);
        while (usageMatcher.find()) {
            String combination;
            if (usageMatcher.group(1) != null) {
                combination = normalize(usageMatcher.group(1));
            } else {
                String name = usageMatcher.group(2);
                if ("null".equals(name)) {
                    continue; // clearMenuAccelerators() strips accelerators from the system menu bar
                }
                combination = constants.get(name);
                assertThat(combination).isNotNull();
            }
            byCombination.computeIfAbsent(combination, key -> new ArrayList<>())
                .add(lineOf(source, usageMatcher.start()));
        }
        assertThat(byCombination).isNotEmpty();

        String duplicates = byCombination.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(entry -> entry.getKey() + " used at lines " + String.join(", ", entry.getValue()))
            .collect(Collectors.joining("\n"));
        assertThat(duplicates).isEmpty();
    }

    /**
     * Cmd/Ctrl+1..9 jump to a tab through the scene shortcut router (TabKeyboardShortcuts), which
     * runs before any menu accelerator, so a menu item on Shortcut+digit would never fire, or would
     * fire as well. The jump chords have no KeyCodeCombination of their own: their modifier rules
     * differ per platform (Shift tolerated on macOS, exactly Ctrl elsewhere).
     */
    @Test
    void noMenuAcceleratorUsesShortcutDigits() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        List<String> digitCombinations = new ArrayList<>();
        Matcher combinationMatcher = Pattern.compile("new KeyCodeCombination\\(([^)]*)\\)").matcher(source);
        int combinations = 0;
        while (combinationMatcher.find()) {
            combinations++;
            if (JUMP_DIGIT.matcher(combinationMatcher.group(1)).find()) {
                digitCombinations.add(normalize(combinationMatcher.group(1))
                    + " at line " + lineOf(source, combinationMatcher.start()));
            }
        }
        assertThat(combinations).isGreaterThan(0);
        assertThat(digitCombinations).isEmpty();
        // The same chords spelled by character or by name.
        assertThat(Pattern.compile("new KeyCharacterCombination\\(\\s*\"[1-9]\"").matcher(source).find()).isFalse();
        assertThat(Pattern.compile("keyCombination\\(\\s*\"[^\"]*\\+(?:Numpad\\s*)?[1-9]\"", Pattern.CASE_INSENSITIVE)
            .matcher(source).find()).isFalse();
        assertThat(source).contains("TabKeyboardShortcuts.slotOf(press) == jumpSlot");
    }

    /**
     * Credentials moved from Shortcut+Shift+P to Shortcut+Shift+M, and Shortcut+Shift+P is the command
     * palette now: its constant is the only Shortcut+Shift+P combination, set on View &gt; Command
     * Palette. Shortcut+M stays Manage Connections. {@link #noTwoMenuItemsShareAnAccelerator()} keeps
     * any other item off the palette's chord.
     */
    @Test
    void credentialsUsesShortcutShiftMAndShortcutShiftPIsTheCommandPalette() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        Map<String, String> constants = new LinkedHashMap<>();
        Matcher constantMatcher = CONSTANT.matcher(source);
        while (constantMatcher.find()) {
            constants.put(constantMatcher.group(1), normalize(constantMatcher.group(2)));
        }
        assertThat(constants).containsEntry("CREDENTIALS_ACCELERATOR", "M+SHIFT_DOWN+SHORTCUT_DOWN");
        assertThat(source).contains("manageCredentials.setAccelerator(CREDENTIALS_ACCELERATOR);");

        List<String> shortcutShiftP = new ArrayList<>();
        List<String> shortcutM = new ArrayList<>();
        Matcher combinationMatcher = Pattern.compile("new KeyCodeCombination\\(([^)]*)\\)").matcher(source);
        while (combinationMatcher.find()) {
            String combination = normalize(combinationMatcher.group(1));
            if (combination.equals("P+SHIFT_DOWN+SHORTCUT_DOWN")) {
                shortcutShiftP.add("line " + lineOf(source, combinationMatcher.start()));
            } else if (combination.equals("M+SHORTCUT_DOWN")) {
                shortcutM.add(lineOf(source, combinationMatcher.start()));
            }
        }
        assertThat(constants).containsEntry("COMMAND_PALETTE_ACCELERATOR", "P+SHIFT_DOWN+SHORTCUT_DOWN");
        assertThat(shortcutShiftP).hasSize(1);
        assertThat(source).containsMatch("static final KeyCombination COMMAND_PALETTE_ACCELERATOR\\s*=\\s*"
            + "new KeyCodeCombination\\(\\s*KeyCode\\.P,\\s*KeyCombination\\.SHORTCUT_DOWN,\\s*KeyCombination\\.SHIFT_DOWN\\s*\\)");
        assertThat(source).contains("commandPalette.setAccelerator(COMMAND_PALETTE_ACCELERATOR);");
        assertThat(shortcutM).hasSize(1);
        assertThat(source).contains(
            "manageConnections.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));");
    }

    /**
     * The user's shortcut overrides go through {@link KeymapOverrides}, which compares chords by the
     * keys they press on each platform (Shortcut is Ctrl on Windows and Linux, Cmd on macOS). The
     * default keymap must already be unique by that measure on every platform, or resolving the
     * overrides could not end with a keymap that holds no chord twice.
     */
    @Test
    void theDefaultKeymapHoldsNoChordTwiceOnAnyPlatform() throws IOException {
        Map<String, KeyChord> defaults = defaultKeymap();
        assertThat(defaults.size()).isAtLeast(40);
        for (Os os : Os.values()) {
            assertWithMessage("default chords colliding on %s", os)
                .that(KeymapOverrides.conflicts(defaults, os)).isEmpty();
        }
    }

    /**
     * The effective keymap, not just the source, is what has to be unique: an override that takes
     * another action's chord (or a fixed one, or a key of the terminal) is dropped, so the menu items
     * never end up sharing an accelerator.
     */
    @Test
    void theEffectiveKeymapHoldsNoChordTwiceWhateverTheOverrides() throws IOException {
        Map<String, KeyChord> defaults = defaultKeymap();
        assertThat(defaults).containsEntry("menu.view.commandPalette", KeyChord.parse("Shortcut+Shift+P"));
        assertThat(defaults).containsEntry("menu.file.newTab", KeyChord.parse("Shortcut+T"));
        Map<String, KeyChord> fixed = new LinkedHashMap<>();
        Map<String, KeyChord> rebindable = new LinkedHashMap<>();
        defaults.forEach((id, chord) -> (KeymapSupport.FIXED_ACTION_IDS.contains(id) ? fixed : rebindable).put(id, chord));
        assertThat(fixed.keySet()).containsAtLeast("menu.edit.cut", "menu.edit.copy", "menu.edit.paste",
            "menu.view.zoomIn", "menu.view.fullscreen");

        List<String> overrides = List.of("menu.file.newTab=Shortcut+Shift+P", "menu.view.dashboard=Shortcut+V",
            "menu.file.renameTab=Shortcut+Shift+R", "menu.view.commandPalette=Shortcut+Shift+J");
        for (Os os : Os.values()) {
            KeymapOverrides.Resolution keymap = KeymapOverrides.parse(overrides)
                .resolve(rebindable, KeymapSupport.rules(os, fixed));
            Map<String, KeyChord> effective = new LinkedHashMap<>(keymap.effective());
            effective.putAll(fixed);
            assertWithMessage("effective keymap on %s", os).that(KeymapOverrides.conflicts(effective, os)).isEmpty();
            assertWithMessage("Paste's Shortcut+V cannot be taken on %s", os)
                .that(keymap.chord("menu.view.dashboard")).isEqualTo(defaults.get("menu.view.dashboard"));
        }
    }

    /**
     * MainWindow applies the keymap in effect to the in-window menu bar (so the menus and the command
     * palette show it) and to the scene shortcut router, when the window is built and whenever the
     * settings change, in every open window.
     */
    @Test
    void mainWindowAppliesTheEffectiveKeymapToTheMenuBarAndTheRouter() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        String apply = body(source, "    void applyKeymap() {");
        assertThat(apply).contains("KeymapOverrides.parse(settings.getKeyBindingOverrides())");
        assertThat(apply).contains("KeymapSupport.defaults(menuBar.getMenus(), os)");
        assertThat(apply).contains("overrides.resolve(defaults.rebindable(),\n"
            + "            KeymapSupport.rules(os, defaults.fixed()));");
        assertThat(apply).contains("List<MenuItem> rechorded = KeymapSupport.applyToMenus(menuBar.getMenus(), keymap);");
        assertWithMessage("a shown window's scene accelerators get the changed items' actions back")
            .that(apply).contains("KeymapSupport.reinstallAccelerators(menuBarScene.getAccelerators(), rechorded);");
        assertThat(apply).contains("for (RoutedChord chord : routedChords()) {\n            chord.bind(keymap);");
        assertWithMessage("the other menu commands on a chord the user chose")
            .that(apply).contains("reboundMenuChords = KeymapSupport.reboundItems(menuBar.getMenus(), routedIds);");
        assertWithMessage("run over a focused terminal, which would encode the key for the shell, residue swallowed")
            .that(body(source, "    private SceneShortcutRouter createSceneShortcutRouter() {"))
            .contains(".consume(this::matchReboundMenuChord, this::isKeyboardInSelectedTerminal,\n"
                + "                this::runReboundMenuChord, Residue.anyCharacter())");
        assertWithMessage("after the key event, the way the accelerator runs the item")
            .that(body(source, "    private void runReboundMenuChord() {"))
            .contains("Platform.runLater(() -> de.kortty.ui.actions.MenuItemActivation.activate(item));");

        String setup = body(source, "    private void setupMenuBar() {");
        assertWithMessage("after the macOS system bar lost its accelerators, which it keeps losing")
            .that(setup.indexOf("applyKeymap();")).isGreaterThan(setup.indexOf("clearMenuBarAccelerators(systemMenuBar);"));
        assertThat(source).contains("// The shortcut overrides may have changed: menu accelerators and router chords.\n"
            + "                refreshKeymapInAllWindows();");
        assertThat(body(source, "    static void refreshKeymapInAllWindows() {")).contains("window.applyKeymap();");

        String routed = body(source, "    private List<RoutedChord> routedChords() {");
        for (String field : List.of("commandPaletteChord", "menuBarToggleChord", "terminalOnlyFullscreenChord",
            "highlightingToggleChord", "credentialsChord", "reopenClosedTabChord", "quickSelectChord")) {
            assertWithMessage(field).that(routed).contains(field);
            assertWithMessage(field + " in the router").that(body(source,
                "    private SceneShortcutRouter createSceneShortcutRouter() {")).contains(field + "::matches");
        }
        assertThat(routed).contains("chords.addAll(paneChords.values());");
    }

    /**
     * Every accelerator MainWindow sets on a menu item, keyed by the item's action id where the item is
     * built with {@code menuItem}, {@code checkMenuItem} or {@code ActionIds.tag}, else by its source line.
     */
    private static Map<String, KeyChord> defaultKeymap() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher constantMatcher = CONSTANT.matcher(source);
        while (constantMatcher.find()) {
            constants.put(constantMatcher.group(1), normalize(constantMatcher.group(2)));
        }
        Map<String, String> idsByVariable = new LinkedHashMap<>();
        Matcher declaration = Pattern.compile("(\\w+) = (?:menuItem|checkMenuItem)\\(\"([^\"]+)\"\\)"
            + "|(\\w+) = ActionIds\\.tag\\(new (?:Check)?MenuItem\\(I18n\\.get\\(\"[^\"]+\"\\)\\),\\s*\"([^\"]+)\"\\)")
            .matcher(source);
        while (declaration.find()) {
            if (declaration.group(1) != null) {
                idsByVariable.put(declaration.group(1), declaration.group(2));
            } else {
                idsByVariable.put(declaration.group(3), declaration.group(4));
            }
        }
        Map<String, KeyChord> keymap = new LinkedHashMap<>();
        Matcher usage = Pattern.compile("([\\w.()]+)\\.setAccelerator\\(\\s*(?:new KeyCodeCombination\\(([^)]*)\\)|(\\w+))\\s*\\)")
            .matcher(source);
        while (usage.find()) {
            String combination = usage.group(2) != null ? normalize(usage.group(2)) : constants.get(usage.group(3));
            if (combination == null) {
                continue; // setAccelerator(null) on the macOS system bar, or a variable outside the menus
            }
            String id = idsByVariable.getOrDefault(usage.group(1), "line " + lineOf(source, usage.start()));
            if (usage.group(1).equals("toggle")) {
                id = "menu.view.highlighting.toggle";
            }
            KeyChord chord = chordOf(combination);
            assertWithMessage("%s = %s", id, combination).that(chord).isNotNull();
            keymap.put(id, chord);
        }
        return keymap;
    }

    /** The chord of a normalized KeyCodeCombination argument list such as {@code M+SHIFT_DOWN+SHORTCUT_DOWN}. */
    private static KeyChord chordOf(String combination) {
        boolean shortcut = false;
        boolean control = false;
        boolean shift = false;
        boolean alt = false;
        String key = null;
        for (String part : combination.split("\\+")) {
            switch (part) {
                case "SHORTCUT_DOWN", "META_DOWN" -> shortcut = true;
                case "CONTROL_DOWN" -> control = true;
                case "SHIFT_DOWN" -> shift = true;
                case "ALT_DOWN" -> alt = true;
                default -> key = KeyChord.keyForJavaFxName(part);
            }
        }
        return key == null ? null : new KeyChord(shortcut, control, shift, alt, key);
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage(signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    /** Turns a KeyCodeCombination argument list into a canonical, order-independent key. */
    private static String normalize(String arguments) {
        return Arrays.stream(arguments.split(","))
            .map(String::trim)
            .filter(argument -> !argument.isEmpty())
            .map(argument -> argument.replace("KeyCombination.", "").replace("KeyCode.", ""))
            .sorted()
            .collect(Collectors.joining("+"));
    }

    private static String lineOf(String source, int offset) {
        return String.valueOf(source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1);
    }
}
