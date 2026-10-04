package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.TerminalActionPresentation;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.SystemSettingsProvider;
import de.kortty.model.ConnectionSettings;
import de.kortty.shellintegration.PromptNavigator.Direction;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCombination.ModifierValue;
import org.testng.annotations.Test;

/**
 * Previous Prompt and Next Prompt are both a menu item of the main window and a key action of every
 * terminal pane. Both must use the same keys, the ones {@link MainWindow} declares (so
 * {@code MainWindowAcceleratorUniquenessTest} covers them), and the pane's actions must not take a
 * key from SithTermFX's own actions: SithTermFX runs the first action whose key matches, and korTTY's
 * come first.
 *
 * <p>Toolkit-free: key combinations are compared by their parts, because
 * {@link KeyCombination#match} asks the JavaFX toolkit for the shortcut key.
 */
class TerminalActionMenuConflictTest {

    @Test
    void theKeysAreCmdOrCtrlWithShiftAndTheArrows() {
        assertThat(MainWindow.previousPromptAccelerator())
            .isEqualTo(new KeyCodeCombination(KeyCode.UP, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        assertThat(MainWindow.nextPromptAccelerator())
            .isEqualTo(new KeyCodeCombination(KeyCode.DOWN, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
    }

    @Test
    void thePaneActionsUseExactlyTheMenuKeys() throws IOException {
        List<TerminalAction> actions = ShellIntegrationController.promptActions(
            MainWindow.previousPromptAccelerator(), MainWindow.nextPromptAccelerator(), () -> true, direction -> { });
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).getPresentation().getKeyCombinations())
            .containsExactly(MainWindow.previousPromptAccelerator());
        assertThat(actions.get(1).getPresentation().getKeyCombinations())
            .containsExactly(MainWindow.nextPromptAccelerator());

        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertThat(view).contains("TerminalView::isShellIntegrationEnabled, MainWindow.previousPromptAccelerator(), "
            + "MainWindow.nextPromptAccelerator());");
        String window = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java"), StandardCharsets.UTF_8);
        assertThat(window).contains("previousPrompt.setAccelerator(PREVIOUS_PROMPT_ACCELERATOR);");
        assertThat(window).contains("nextPrompt.setAccelerator(NEXT_PROMPT_ACCELERATOR);");
    }

    @Test
    void theActionsJumpOnlyWhileAvailableAndStayOutOfMenus() {
        AtomicBoolean available = new AtomicBoolean(false);
        List<Direction> jumps = new ArrayList<>();
        List<TerminalAction> actions = ShellIntegrationController.promptActions(
            MainWindow.previousPromptAccelerator(), MainWindow.nextPromptAccelerator(), available::get, jumps::add);

        for (TerminalAction action : actions) {
            assertWithMessage("a disabled action leaves the key to the program in the pane")
                .that(action.isEnabled(null)).isFalse();
            assertWithMessage("korTTY builds the context menu itself").that(action.isHidden()).isTrue();
        }
        available.set(true);
        assertThat(actions.get(0).isEnabled(null)).isTrue();
        assertThat(actions.get(0).actionPerformed(null)).isTrue();
        assertThat(actions.get(1).actionPerformed(null)).isTrue();
        assertThat(jumps).containsExactly(Direction.PREVIOUS, Direction.NEXT).inOrder();
    }

    @Test
    void theWidgetTriesKorttysActionsFirst() throws IOException {
        String widget = Files.readString(Path.of("src/main/java/de/kortty/ui/KorttyTermWidget.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        int own = widget.indexOf("all.addAll(own);");
        int vendor = widget.indexOf("all.addAll(actions);");
        assertThat(own).isAtLeast(0);
        assertWithMessage("korTTY's actions come before SithTermFX's").that(vendor).isGreaterThan(own);
        assertThat(widget).contains("public List<TerminalAction> getActions() {");
    }

    @Test
    void noSithTermFxActionSharesTheirKeysOnEitherPlatform() throws Exception {
        List<KeyCombination> ours = List.of(MainWindow.previousPromptAccelerator(), MainWindow.nextPromptAccelerator());
        boolean hostIsMac = com.sithtermfx.core.util.Platform.isMacOS();
        SystemSettingsProvider provider = korttyProvider();
        List<TerminalActionPresentation> hostPresentations = List.of(
            provider.getOpenUrlActionPresentation(), provider.getCopyActionPresentation(),
            provider.getPasteActionPresentation(), provider.getClearBufferActionPresentation(),
            provider.getPageUpActionPresentation(), provider.getPageDownActionPresentation(),
            provider.getLineUpActionPresentation(), provider.getLineDownActionPresentation(),
            provider.getFindActionPresentation(), provider.getSelectAllActionPresentation());
        for (TerminalActionPresentation presentation : hostPresentations) {
            for (KeyCombination theirs : presentation.getKeyCombinations()) {
                for (KeyCombination mine : ours) {
                    assertWithMessage(presentation.getName() + " " + theirs + " vs " + mine)
                        .that(collide(mine, theirs, hostIsMac)).isFalse();
                }
            }
        }
        // SithTermFX's scroll keys on both platforms: Cmd+Up/Down on macOS, Ctrl+Up/Down elsewhere,
        // and Shift+Page Up/Down; korTTY's own Clear Buffer and Find on both platforms.
        List<KeyCombination> scrollKeys = List.of(
            new KeyCodeCombination(KeyCode.UP, KeyCombination.META_DOWN),
            new KeyCodeCombination(KeyCode.DOWN, KeyCombination.META_DOWN),
            new KeyCodeCombination(KeyCode.UP, KeyCombination.CONTROL_DOWN),
            new KeyCodeCombination(KeyCode.DOWN, KeyCombination.CONTROL_DOWN),
            new KeyCodeCombination(KeyCode.PAGE_UP, KeyCombination.SHIFT_DOWN),
            new KeyCodeCombination(KeyCode.PAGE_DOWN, KeyCombination.SHIFT_DOWN));
        for (boolean mac : new boolean[] {true, false}) {
            List<KeyCombination> theirs = new ArrayList<>(scrollKeys);
            theirs.addAll(TerminalView.clearBufferActionPresentation(mac).getKeyCombinations());
            theirs.addAll(TerminalView.findActionPresentation(mac).getKeyCombinations());
            for (KeyCombination other : theirs) {
                for (KeyCombination mine : ours) {
                    assertWithMessage((mac ? "macOS " : "Windows/Linux ") + other + " vs " + mine)
                        .that(collide(mine, other, mac)).isFalse();
                }
            }
        }
    }

    @Test
    void neverAPlainCtrlLetterAndNeverAltGr() {
        for (KeyCombination key : List.of(MainWindow.previousPromptAccelerator(), MainWindow.nextPromptAccelerator())) {
            KeyCodeCombination combination = (KeyCodeCombination) key;
            assertThat(combination.getCode().isLetterKey()).isFalse();
            assertWithMessage("AltGr arrives as Ctrl+Alt on Windows").that(combination.getAlt()).isEqualTo(ModifierValue.UP);
            assertThat(combination.getShift()).isEqualTo(ModifierValue.DOWN);
        }
    }

    /** Whether one key press can match both combinations on the given platform. */
    static boolean collide(KeyCombination a, KeyCombination b, boolean mac) {
        if (!(a instanceof KeyCodeCombination first) || !(b instanceof KeyCodeCombination second)) {
            return false;
        }
        if (first.getCode() != second.getCode()) {
            return false;
        }
        return compatible(first.getShift(), second.getShift())
            && compatible(control(first, mac), control(second, mac))
            && compatible(first.getAlt(), second.getAlt())
            && compatible(meta(first, mac), meta(second, mac));
    }

    private static ModifierValue control(KeyCombination key, boolean mac) {
        return mac ? key.getControl() : merge(key.getControl(), key.getShortcut());
    }

    private static ModifierValue meta(KeyCombination key, boolean mac) {
        return mac ? merge(key.getMeta(), key.getShortcut()) : key.getMeta();
    }

    private static ModifierValue merge(ModifierValue modifier, ModifierValue shortcut) {
        if (modifier == ModifierValue.DOWN || shortcut == ModifierValue.DOWN) {
            return ModifierValue.DOWN;
        }
        return modifier == ModifierValue.ANY || shortcut == ModifierValue.ANY ? ModifierValue.ANY : ModifierValue.UP;
    }

    private static boolean compatible(ModifierValue a, ModifierValue b) {
        return a == b || a == ModifierValue.ANY || b == ModifierValue.ANY;
    }

    private static SystemSettingsProvider korttyProvider() throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> constructor = cls.getDeclaredConstructor(
            ConnectionSettings.class, DynamicFontSizeSettingsProvider.class, java.util.function.IntSupplier.class);
        constructor.setAccessible(true);
        return (SystemSettingsProvider) constructor.newInstance(
            new ConnectionSettings(), new DynamicFontSizeSettingsProvider(14f), (java.util.function.IntSupplier) () -> 0);
    }
}
