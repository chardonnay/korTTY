package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * korTTY's UI font size is user-adjustable, which only works because every chrome font size is
 * expressed relative to the base that {@link UiFontScaleSupport} pins on the scene root. A single
 * absolute {@code px} value opts that node out silently — it simply stays small, with no error to
 * notice — so this guard fails the build instead.
 *
 * <p>Surfaces that own their own font size (the terminal, the editor, the AI chat/plan/activity
 * panels) build their style string from a variable and therefore never match these patterns. They
 * need no allowlist: "absolute size" and "user-controlled size" are already distinguishable by
 * whether the number is a literal.</p>
 */
class NoHardcodedUiFontSizeTest {

    private static final Path JAVA_ROOT = Path.of("src/main/java");
    private static final Path STYLES_ROOT = Path.of("src/main/resources/styles");

    /**
     * An absolute font size: a literal number followed by px/pt, or by nothing at all — JavaFX
     * accepts the unitless form and treats it as pixels, which is how two of these originally hid.
     */
    private static final Pattern ABSOLUTE_FONT_SIZE = Pattern.compile(
        "-fx-font-size:\\s*\\d+(?:\\.\\d+)?\\s*(?:px|pt)?\\s*[;\"']");

    /** Escape hatch for a surface that genuinely must not scale. A reason is mandatory. */
    private static final Pattern OWNED_MARKER = Pattern.compile(
        "ui-font-size:\\s*owned\\s*[-—]\\s*\\S+");

    @Test
    void stylesheetsDeclareNoAbsoluteFontSizes() throws IOException {
        assertNoOffenders(collectOffenders(STYLES_ROOT, ".css"),
            "Stylesheets must express font sizes in em so the UI font scale reaches them.");
    }

    @Test
    void javaSourcesDeclareNoAbsoluteFontSizes() throws IOException {
        assertNoOffenders(collectOffenders(JAVA_ROOT, ".java"),
            "Inline styles must express font sizes in em so the UI font scale reaches them.");
    }

    /**
     * The em values are relative to the <em>parent's</em> computed size, so a sized rule nested
     * inside another sized rule multiplies them together and lands somewhere nobody intended.
     * A value of exactly 1em is the identity and therefore always safe.
     */
    @Test
    void noRelativeFontSizeNestsInsideAnotherSizedSelector() throws IOException {
        Pattern block = Pattern.compile("([^{}]+)\\{([^}]*)}", Pattern.DOTALL);
        Pattern relative = Pattern.compile("-fx-font-size:\\s*([0-9.]+)em");
        List<String> problems = new ArrayList<>();

        for (Path sheet : listFiles(STYLES_ROOT, ".css")) {
            List<String[]> sized = new ArrayList<>(); // {selector, value}
            // Comments have to go first: the text between one rule's "}" and the next rule's "{"
            // includes any comment in between, which would otherwise be read as part of the
            // selector and quietly exclude that rule from the check.
            String css = Files.readString(sheet, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ");
            Matcher blocks = block.matcher(css);
            while (blocks.find()) {
                Matcher value = relative.matcher(blocks.group(2));
                if (!value.find()) {
                    continue;
                }
                for (String selector : blocks.group(1).split(",")) {
                    String normalized = selector.trim().replaceAll("\\s+", " ");
                    if (!normalized.isEmpty()) {
                        sized.add(new String[] {normalized, value.group(1)});
                    }
                }
            }
            for (String[] child : sized) {
                if (Double.parseDouble(child[1]) == 1.0) {
                    continue;
                }
                for (String[] ancestor : sized) {
                    if (!child[0].equals(ancestor[0]) && child[0].startsWith(ancestor[0] + " ")) {
                        problems.add(sheet.getFileName() + ": '" + child[0] + "' (" + child[1]
                            + "em) is nested inside '" + ancestor[0] + "' (" + ancestor[1] + "em)");
                    }
                }
            }
        }

        assertWithMessage("Nested relative font sizes multiply. Use 1em on the inner rule to keep "
            + "the parent's size, or give the inner rule a selector that is not a descendant.\n"
            + String.join("\n", problems))
            .that(problems).isEmpty();
    }

    /**
     * The runtime theme stylesheet is generated, so the file scan above cannot see it — and its
     * {@code .menu-bar .menu .label} rule is specific enough to override the scale's base, which is
     * exactly how the menu bar stayed pinned at 13px before this feature.
     */
    @Test
    void generatedThemeStylesheetDeclaresNoFontSize() {
        assertThat(ThemeCssSupport.buildCss("#1f2933", "#d9e2ec")).doesNotContain("-fx-font-size");
    }

    /**
     * The scale's own stylesheet must be the only place an absolute base is declared, and it must
     * declare it on all three subtree roots — otherwise some surface silently keeps modena's base.
     */
    @Test
    void generatedScaleStylesheetDeclaresTheBaseOnEveryAnchor() {
        String css = UiFontScaleSupport.buildCss(100);

        assertThat(css).contains("13.00px");
        assertThat(css).contains(".root");
        assertThat(css).contains(".dialog-pane");
        assertThat(css).contains(".context-menu");
    }

    private static void assertNoOffenders(List<String> offenders, String what) {
        assertWithMessage(what + " Convert the value to em (px / "
            + (int) UiFontScaleSupport.BASE_FONT_PX + "), or — if this surface owns its own font "
            + "size — mark the line `ui-font-size: owned — <reason>`.\n"
            + String.join("\n", offenders))
            .that(offenders).isEmpty();
    }

    private static List<String> collectOffenders(Path root, String extension) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : listFiles(root, extension)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (ABSOLUTE_FONT_SIZE.matcher(line).find() && !OWNED_MARKER.matcher(line).find()) {
                    offenders.add(file + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        return offenders;
    }

    private static List<Path> listFiles(Path root, String extension) throws IOException {
        assertWithMessage("Expected to run from the repository root; %s is missing", root)
            .that(Files.isDirectory(root)).isTrue();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(extension))
                .sorted()
                .toList();
        }
    }
}
