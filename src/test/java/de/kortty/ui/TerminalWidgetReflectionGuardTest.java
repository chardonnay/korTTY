package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Regression guard for the dead terminal context menu. {@code TerminalSplitPane} and
 * {@code TerminalView} looked up SithTermFX methods with {@code getClass().getDeclaredMethod(...)}.
 * The terminal widget is {@link KorttyTermWidget}, whose panel is a subclass of its own. Neither
 * runtime class declares those methods, so every lookup threw {@code NoSuchMethodException} into a
 * log line and Copy, Paste, Clear Buffer, Find and the font-size entries silently did nothing. The
 * font lookup also asked for {@code int} parameters where SithTermFX takes {@code float}.
 *
 * <p>The menus need a live JavaFX scene, so this test reads the sources, like
 * {@code MainWindowAcceleratorUniquenessTest}.
 */
public class TerminalWidgetReflectionGuardTest {

    private static final List<Path> SOURCES = List.of(
        Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java"),
        Path.of("src/main/java/de/kortty/ui/TerminalView.java"));

    /** A declared-method lookup on whatever subclass the object happens to be. */
    private static final Pattern RUNTIME_CLASS_DECLARED_LOOKUP = Pattern.compile("getClass\\(\\)\\s*\\.\\s*getDeclaredMethod\\(");

    /** A reflective method lookup with an {@code int} parameter, as the dead font-size lookup used. */
    private static final Pattern INT_PARAMETER_LOOKUP = Pattern.compile("get(?:Declared)?Method\\([^;]*int\\.class");

    /** A reflective lookup of a font-size method by name; the public SithTermFX API is called directly. */
    private static final Pattern FONT_METHOD_BY_NAME = Pattern.compile("\"(?:increase|decrease|reset)FontSize\"");

    @Test
    public void terminalSourcesDoNotLookUpDeclaredMethodsOnTheRuntimeClass() throws IOException {
        assertThat(findings(RUNTIME_CLASS_DECLARED_LOOKUP)).isEmpty();
    }

    @Test
    public void terminalSourcesDoNotLookUpFontMethodsReflectively() throws IOException {
        assertThat(findings(INT_PARAMETER_LOOKUP)).isEmpty();
        assertThat(findings(FONT_METHOD_BY_NAME)).isEmpty();
    }

    @Test
    public void korttyWidgetProvidesThePaneActions() {
        assertWithMessage("the context menu only offers its edit entries for TerminalPaneActions widgets")
            .that(TerminalPaneActions.class.isAssignableFrom(KorttyTermWidget.class)).isTrue();
    }

    /**
     * TerminalView still reads the live selection through SithTermFX's private
     * {@code TerminalPanel.getSelectedText()}. It must be looked up on the declaring class, and that
     * method has to keep existing, or the read quietly falls back to the property.
     */
    @Test
    public void privateSelectedTextReaderStillExistsOnTheDeclaringClass() throws Exception {
        Method method = com.sithtermfx.ui.TerminalPanel.class.getDeclaredMethod("getSelectedText");
        assertThat(method.getReturnType()).isEqualTo(String.class);

        String source = Files.readString(SOURCES.get(1), StandardCharsets.UTF_8);
        assertThat(source).contains("com.sithtermfx.ui.TerminalPanel.class.getDeclaredMethod(\"getSelectedText\")");
    }

    /**
     * Tripwire for a SithTermFX upgrade. korTTY takes every terminal paste out of SithTermFX by
     * overriding the public {@code handlePaste()} and by consuming the middle-click before
     * {@code doOnMouseClicked} sees it. SithTermFX's own paste code behind them is private, so
     * korTTY cannot override it: {@code handlePasteSelection()} and {@code pasteFromClipboard(boolean)},
     * which sends embedded paste markers on unchanged. If a new version moves or reshapes these
     * routes, a paste may reach the pane without korTTY, so the new paths have to be checked again.
     */
    @Test
    void sithTermFxPasteRoutesAreStillTheOnesKorttyTakesOver() throws Exception {
        Class<?> panel = com.sithtermfx.ui.TerminalPanel.class;
        assertThat(Modifier.isPrivate(panel.getDeclaredMethod("pasteFromClipboard", boolean.class).getModifiers()))
            .isTrue();
        assertThat(Modifier.isPrivate(panel.getDeclaredMethod("handlePasteSelection").getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(
            panel.getDeclaredMethod("doOnMouseClicked", javafx.scene.input.MouseEvent.class).getModifiers())).isTrue();

        Method handlePaste = panel.getDeclaredMethod("handlePaste");
        assertThat(Modifier.isPublic(handlePaste.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(handlePaste.getModifiers())).isFalse();
        Method setBracketed = panel.getDeclaredMethod("setBracketedPasteMode", boolean.class);
        assertThat(Modifier.isPublic(setBracketed.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(setBracketed.getModifiers())).isFalse();

        Class<?> korttyPanel = KorttyTermWidget.KorttyTerminalPanel.class;
        assertThat(korttyPanel.getDeclaredMethod("handlePaste").getDeclaringClass()).isEqualTo(korttyPanel);
        assertThat(korttyPanel.getDeclaredMethod("setBracketedPasteMode", boolean.class).getDeclaringClass())
            .isEqualTo(korttyPanel);
    }

    private static List<String> findings(Pattern pattern) throws IOException {
        List<String> findings = new ArrayList<>();
        for (Path source : SOURCES) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                findings.add(source.getFileName() + ":" + lineOf(text, matcher.start()) + " " + matcher.group());
            }
        }
        return findings;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
