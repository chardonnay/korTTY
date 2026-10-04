package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How MainWindow wires the command palette, pinned against the source because the window cannot be
 * built without a stage: the chord constant, its scene shortcut router entry (so it works over a
 * focused terminal and with the menu bar hidden, and leaves no residue behind), the View menu item
 * that shows it, the popup's pass-through, and the state refresh before the palette reads the menus.
 * It also guards the shell's keys: no chord of the router is a plain Shortcut+letter, which on
 * Windows and Linux would take Ctrl+L, Ctrl+F, Ctrl+D, Ctrl+P or Ctrl+R away from the shell.
 */
class CommandPaletteShortcutTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Pattern CONSTANT = Pattern.compile(
        "static final KeyCombination (\\w+)\\s*=\\s*new KeyCodeCombination\\(([^)]*)\\)");

    @Test
    void thePaletteChordIsShortcutShiftP() throws IOException {
        Map<String, String> constants = constants(source());

        assertThat(constants).containsEntry("COMMAND_PALETTE_ACCELERATOR", "P+SHIFT_DOWN+SHORTCUT_DOWN");
        assertThat(source()).contains("static KeyCombination commandPaletteAccelerator() {\n"
            + "        return COMMAND_PALETTE_ACCELERATOR;");
    }

    @Test
    void theRouterTakesTheChordFirstAndSwallowsItsResidue() throws IOException {
        String source = source();
        String router = routerBody(source);

        // The chord in effect: COMMAND_PALETTE_ACCELERATOR unless the user rebound it.
        assertThat(source).contains("private final RoutedChord commandPaletteChord =\n"
            + "        new RoutedChord(\"menu.view.commandPalette\", COMMAND_PALETTE_ACCELERATOR, PaletteKeys.RESIDUE);");
        int palette = router.indexOf(".consume(commandPaletteChord::matches, "
            + "SceneShortcutRouter.ALWAYS,\n                this::toggleCommandPalette, commandPaletteChord::residue)");
        assertWithMessage("router entry for the palette").that(palette).isAtLeast(0);
        assertWithMessage("the palette entry comes before every other entry")
            .that(palette).isLessThan(router.indexOf(".consume(menuBarToggleChord::matches"));
        assertThat(router.indexOf("SceneShortcutKeys::isZoomIn")).isGreaterThan(palette);
    }

    @Test
    void theViewMenuShowsThePaletteAndItIsNoCommandOfItsOwn() throws IOException {
        String view = methodBody(source(), "private Menu createViewMenu(MenuBarTarget target) {");

        assertThat(view).contains("MenuItem commandPalette = menuItem(\"menu.view.commandPalette\");");
        assertThat(view).contains("commandPalette.setAccelerator(COMMAND_PALETTE_ACCELERATOR);");
        assertThat(view).contains("commandPalette.setOnAction(e -> Platform.runLater(this::showCommandPalette));");
        assertThat(view).contains("ActionIds.exclude(commandPalette);");
        assertThat(view).contains("viewMenu.getItems().addAll(commandPalette, snippetPalette, new SeparatorMenuItem(),");
    }

    @Test
    void thePaletteRefreshesTheMenusAndLetsOnlyItsChordThrough() throws IOException {
        String source = source();
        String show = methodBody(source,
            "private void showCommandPalette(de.kortty.ui.actions.PaletteEntry.@Nullable Kind scope) {");
        assertWithMessage("states are refreshed before the palette reads them")
            .that(show.indexOf("refreshActionStates();"))
            .isLessThan(show.indexOf("commandPalette.show(sceneRoot, scope);"));
        assertWithMessage("the chord in effect, read per key event, so a rebinding reaches an open palette")
            .that(show).contains("PaletteKeys.passThrough(commandPaletteChord::chord, isMacOs())");
        assertThat(show).contains("new ActionPaletteSource(actionRegistry(), KeyCombination::getDisplayText,");

        String refresh = methodBody(source, "private void refreshActionStates() {");
        for (String sync : List.of("syncUnlockVaultMenuItems();", "syncAiFeaturesMenuItemsEnabled();",
                "syncPreventSleepMenuItems();", "updateEditMenuItemsForSelection();",
                "syncHighlightingToggleItems();", "MenuStateRefresh.refresh(menuBar.getMenus());")) {
            assertThat(refresh).contains(sync);
        }
        assertWithMessage("the timestamps check mark follows the selected terminal tab")
            .that(refresh).contains("syncTimestampMenuItems(active.isTimestampGuttersVisible());");

        String registry = methodBody(source, "private ActionRegistry actionRegistry() {");
        assertWithMessage("the palette harvests the in-window menu bar")
            .that(registry).contains("MenuActionHarvester.harvest(menuBar.getMenus())");
        assertThat(registry).contains("\"palette.action.nextTab\"");
        assertThat(registry).contains("\"palette.action.previousTab\"");

        String toggle = methodBody(source, "private void toggleCommandPalette() {");
        assertThat(toggle).contains("commandPalette.hide();");
    }

    @Test
    void theRunHappensAfterThePopupClosed() throws IOException {
        String popup = Files.readString(Path.of("src/main/java/de/kortty/ui/CommandPalettePopup.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(popup).contains(".choosable(PaletteEntry::enabled)");
        assertThat(popup).contains(".onUnchoosable(this::showReason)");
        assertThat(popup).contains(".passThrough(passThrough)");
        assertThat(methodBody(popup, "private void run(PaletteEntry entry) {"))
            .contains("Platform.runLater(entry.run());");
    }

    /** A hidden palette holds no rows, which point to tabs, terminals and snippets that may close. */
    @Test
    void aHiddenPaletteLetsGoOfItsRows() throws IOException {
        String popup = Files.readString(Path.of("src/main/java/de/kortty/ui/CommandPalettePopup.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        String picker = Files.readString(Path.of("src/main/java/de/kortty/ui/QuickPickPopup.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(popup).contains(".onHidden(model::close)");
        assertThat(picker).contains("popup.setOnHidden(event -> {\n"
            + "            list.getItems().clear();\n"
            + "            onHidden.run();\n"
            + "        });");
    }

    /**
     * The router consumes before the focused terminal sees a key, so a plain Shortcut+letter there
     * would take a control character away from the shell on Windows and Linux. Every default chord
     * the router consumes needs Shift or Alt as well; the Edit &gt; Find menu accelerator (Shortcut+F)
     * is no router entry and only fires for keys the terminal left alone. A chord the user chooses
     * instead is held to the same rule by KeymapOverrides (RESERVED_SHELL, KeymapOverridesTest).
     */
    @Test
    void noRouterChordIsAPlainShortcutLetter() throws IOException {
        String source = source();
        Map<String, String> constants = constants(source);
        String router = routerBody(source);

        // A rebindable entry's default is the constant its RoutedChord field is built on.
        Map<String, String> routedDefaults = new LinkedHashMap<>();
        Matcher field = Pattern.compile(
            "private final RoutedChord (\\w+) =\\s*new RoutedChord\\(\\s*[^,]+,\\s*(\\w+),").matcher(source);
        while (field.find()) {
            routedDefaults.put(field.group(1), field.group(2));
        }
        // An entry without a default chord (RoutedChord.unbound) consumes only a chord the user chose.
        List<String> unbound = new ArrayList<>();
        Matcher unboundField = Pattern.compile("private final RoutedChord (\\w+) = RoutedChord\\.unbound\\(").matcher(source);
        while (unboundField.find()) {
            unbound.add(unboundField.group(1));
        }
        assertThat(unbound).contains("snippetPaletteChord");
        List<String> paneDefaults = new ArrayList<>();
        Matcher pane = Pattern.compile("PaneShortcuts\\.PaneAction\\.\\w+, (PANE_\\w+_ACCELERATOR)").matcher(source);
        while (pane.find()) {
            paneDefaults.add(pane.group(1));
        }
        assertThat(paneDefaults).hasSize(PaneShortcuts.PaneAction.values().length);

        // Consuming entries only: the Paste observer lets its Shortcut+V go on to the terminal.
        Matcher used = Pattern.compile("\\.consume\\((?:press -> (?:press\\.matches|PaletteKeys\\.isChord)"
            + "\\((?:press, )?(\\w+)\\)|(\\w+)::matches)").matcher(router);
        List<String> plain = new ArrayList<>();
        int chords = 0;
        while (used.find()) {
            List<String> names = new ArrayList<>();
            if (used.group(1) != null) {
                names.add(used.group(1));
            } else if (unbound.contains(used.group(2))) {
                continue;
            } else if ("paneChord".equals(used.group(2))) {
                names.addAll(paneDefaults);
            } else {
                names.add(routedDefaults.get(used.group(2)));
            }
            for (String name : names) {
                chords++;
                String combination = constants.get(name);
                assertWithMessage("router chord " + name + " is a KeyCodeCombination constant")
                    .that(combination).isNotNull();
                boolean letter = combination.matches("(?:.*\\+)?[A-Z](?:\\+.*)?");
                boolean extraModifier = combination.contains("SHIFT_DOWN") || combination.contains("ALT_DOWN");
                if (letter && combination.contains("SHORTCUT_DOWN") && !extraModifier) {
                    plain.add(name + " = " + combination);
                }
            }
        }
        assertThat(chords).isAtLeast(12);
        assertThat(plain).isEmpty();
    }

    private static String source() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Map<String, String> constants(String source) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher matcher = CONSTANT.matcher(source);
        while (matcher.find()) {
            constants.put(matcher.group(1), normalize(matcher.group(2)));
        }
        return constants;
    }

    private static String routerBody(String source) {
        return methodBody(source, "private SceneShortcutRouter createSceneShortcutRouter() {");
    }

    private static String normalize(String arguments) {
        return java.util.Arrays.stream(arguments.split(","))
            .map(String::trim)
            .filter(argument -> !argument.isEmpty())
            .map(argument -> argument.replace("KeyCombination.", "").replace("KeyCode.", ""))
            .sorted()
            .collect(java.util.stream.Collectors.joining("+"));
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
