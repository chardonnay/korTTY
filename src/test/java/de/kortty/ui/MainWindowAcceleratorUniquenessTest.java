package de.kortty.ui;

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
