package de.kortty.ui;

import com.sithtermfx.core.util.Platform;
import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.TerminalActionPresentation;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.SystemSettingsProvider;
import de.kortty.model.ConnectionSettings;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression guard: on Windows/Linux the vendor binds the terminal's own Clear Buffer action to
 * Ctrl+L and Find to Ctrl+F, and the canvas key filter consumes them, so ^L (redraw/clear screen in
 * bash, psql, REPLs) and ^F (readline forward-char, less/vim page forward) never reached the shell.
 * korTTY's settings provider strips those bindings there and keeps the macOS Cmd+K / Cmd+F ones.
 *
 * <p>Toolkit-free: only presentations, {@link TerminalAction#matches} on an action with no key
 * combinations (which never consults the toolkit) and a reflectively built provider are used.
 */
class TerminalActionBindingsTest {

    private static final KeyCodeCombination CMD_K = new KeyCodeCombination(KeyCode.K, KeyCombination.META_DOWN);
    private static final KeyCodeCombination CMD_F = new KeyCodeCombination(KeyCode.F, KeyCombination.META_DOWN);

    @Test
    void clearBufferHasNoKeyBindingOnWindowsAndLinux() {
        TerminalActionPresentation presentation = TerminalView.clearBufferActionPresentation(false);

        assertThat(presentation.getKeyCombinations()).isEmpty();
        assertThat(presentation.getName()).isEqualTo(I18n.get("terminal.contextMenu.clearBuffer"));
    }

    @Test
    void findHasNoKeyBindingOnWindowsAndLinux() {
        TerminalActionPresentation presentation = TerminalView.findActionPresentation(false);

        assertThat(presentation.getKeyCombinations()).isEmpty();
        assertThat(presentation.getName()).isEqualTo(I18n.get("terminal.contextMenu.find"));
    }

    @Test
    void ctrlLAndCtrlFAreNoLongerClaimedByTheTerminalActions() {
        KeyEvent ctrlL = new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "\f", KeyCode.L, false, true, false, false);
        KeyEvent ctrlF = new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "\u0006", KeyCode.F, false, true, false, false);

        assertThat(new TerminalAction(TerminalView.clearBufferActionPresentation(false), e -> true).matches(ctrlL)).isFalse();
        assertThat(new TerminalAction(TerminalView.findActionPresentation(false), e -> true).matches(ctrlF)).isFalse();
    }

    @Test
    void macOsKeepsCmdKAndCmdF() {
        assertThat(TerminalView.clearBufferActionPresentation(true).getKeyCombinations()).containsExactly(CMD_K);
        assertThat(TerminalView.findActionPresentation(true).getKeyCombinations()).containsExactly(CMD_F);
    }

    @Test
    void providerFollowsTheHostPlatform() throws Exception {
        SystemSettingsProvider provider = newProvider();

        if (Platform.isMacOS()) {
            // Unchanged versus the vendor default on macOS.
            DefaultSettingsProvider vendor = new DefaultSettingsProvider();
            assertThat(provider.getClearBufferActionPresentation().getKeyCombinations())
                    .containsExactlyElementsIn(vendor.getClearBufferActionPresentation().getKeyCombinations());
            assertThat(provider.getFindActionPresentation().getKeyCombinations())
                    .containsExactlyElementsIn(vendor.getFindActionPresentation().getKeyCombinations());
        } else {
            assertThat(provider.getClearBufferActionPresentation().getKeyCombinations()).isEmpty();
            assertThat(provider.getFindActionPresentation().getKeyCombinations()).isEmpty();
        }
    }

    @Test
    void providerOverridesBothActionPresentationsOnEveryHost() throws Exception {
        // providerFollowsTheHostPlatform cannot notice a deleted override on a macOS host, where the
        // vendor default is the same Cmd+K / Cmd+F, so pin that the overrides exist at all.
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        assertThat(cls.getDeclaredMethod("getClearBufferActionPresentation").getDeclaringClass()).isEqualTo(cls);
        assertThat(cls.getDeclaredMethod("getFindActionPresentation").getDeclaringClass()).isEqualTo(cls);
    }

    @Test
    void onlyLinuxMirrorsCopyOnSelectIntoThePrimarySelection() {
        assertThat(TerminalView.shouldMirrorToPrimarySelection(Platform.Linux)).isTrue();
        assertThat(TerminalView.shouldMirrorToPrimarySelection(Platform.Windows)).isFalse();
        assertThat(TerminalView.shouldMirrorToPrimarySelection(Platform.macOS)).isFalse();
        assertThat(TerminalView.shouldMirrorToPrimarySelection(Platform.Other)).isFalse();
        assertThat(TerminalView.shouldMirrorToPrimarySelection(null)).isFalse();
    }

    private static SystemSettingsProvider newProvider() throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                ConnectionSettings.class, DynamicFontSizeSettingsProvider.class, java.util.function.IntSupplier.class);
        ctor.setAccessible(true);
        return (SystemSettingsProvider) ctor.newInstance(
                new ConnectionSettings(), new DynamicFontSizeSettingsProvider(14f), (java.util.function.IntSupplier) () -> 0);
    }
}
