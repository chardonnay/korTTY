package de.kortty.ui;

import com.sithtermfx.core.TerminalKeyEncoder;
import com.sithtermfx.core.emulator.EmulationType;
import com.sithtermfx.core.util.Platform;
import de.kortty.core.Mosh4jTtyConnector;
import de.kortty.model.ServerConnection;
import javafx.scene.input.KeyCode;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.IntFunction;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * korTTY used to answer every navigation key with a fixed sequence (arrows always as SS3) and drop
 * the modifiers, so Ctrl+Left, Shift+Tab or Shift+F1 never reached the shell and mc and vim only
 * worked by accident. These cases pin the xterm encoding on top of the terminal's own,
 * mode-dependent sequence, using SithTermFX's encoder as the oracle for that base.
 *
 * <p>Toolkit-free: only the {@link KeyCode} enum is used, no JavaFX control is created.
 */
public class TerminalNavigationKeysTest {

    private static final String ESC = "\u001B";

    @Test
    public void plainArrowFollowsTheCursorKeyMode() {
        TerminalKeyEncoder encoder = new TerminalKeyEncoder(Platform.Linux);

        assertThat(plain(KeyCode.UP, encoder)).isEqualTo(ESC + "[A");

        // ESC[?1h (DECCKM), as mc and vim switch it on: the emulator now wants SS3 arrows.
        encoder.arrowKeysApplicationSequences();
        assertThat(plain(KeyCode.UP, encoder)).isEqualTo(ESC + "OA");
        assertThat(plain(KeyCode.LEFT, encoder)).isEqualTo(ESC + "OD");
    }

    @Test
    public void tabAndShiftTabAreSentButCtrlTabStaysTheTabSwitchShortcut() {
        IntFunction<byte[]> base = base(new TerminalKeyEncoder(Platform.Linux));

        assertThat(text(TerminalNavigationKeys.encode(KeyCode.TAB, false, false, false, false, false, base)))
            .isEqualTo("\t");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.TAB, true, false, false, false, false, base)))
            .isEqualTo(ESC + "[Z");
        assertThat(TerminalNavigationKeys.encode(KeyCode.TAB, false, true, false, false, false, base)).isNull();
        assertThat(TerminalNavigationKeys.encode(KeyCode.TAB, true, true, false, false, false, base)).isNull();
    }

    @Test
    public void wordMovementUsesXtermModifiersOnLinuxAndEscBfOnMacOs() {
        IntFunction<byte[]> linux = base(new TerminalKeyEncoder(Platform.Linux));
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.LEFT, false, true, false, false, false, linux)))
            .isEqualTo(ESC + "[1;5D");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.LEFT, false, false, true, false, false, linux)))
            .isEqualTo(ESC + "[1;3D");

        IntFunction<byte[]> mac = base(new TerminalKeyEncoder(Platform.macOS));
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.LEFT, false, false, true, false, true, mac)))
            .isEqualTo(ESC + "b");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.RIGHT, false, false, true, false, true, mac)))
            .isEqualTo(ESC + "f");
    }

    @Test
    public void aModifiedKeyNeverChangesTheNextPlainOne() {
        // SithTermFX's own encoder rewrites its stored SS3 sequence to CSI when it adds a modifier,
        // so one Shift+Up used to turn every later plain Up into ESC [ A.
        TerminalKeyEncoder encoder = new TerminalKeyEncoder(Platform.Linux);
        encoder.arrowKeysApplicationSequences();
        IntFunction<byte[]> base = base(encoder);

        assertThat(text(TerminalNavigationKeys.encode(KeyCode.UP, true, false, false, false, false, base)))
            .isEqualTo(ESC + "[1;2A");
        assertThat(plain(KeyCode.UP, encoder)).isEqualTo(ESC + "OA");

        assertThat(text(TerminalNavigationKeys.encode(KeyCode.F1, true, false, false, false, false, base)))
            .isEqualTo(ESC + "[1;2P");
        assertThat(plain(KeyCode.F1, encoder)).isEqualTo(ESC + "OP");
    }

    @Test
    public void tildeAndCursorKeysTakeTheModifierParameter() {
        IntFunction<byte[]> base = base(new TerminalKeyEncoder(Platform.Linux));

        assertThat(text(TerminalNavigationKeys.encode(KeyCode.PAGE_UP, false, true, false, false, false, base)))
            .isEqualTo(ESC + "[5;5~");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.DELETE, true, false, false, false, false, base)))
            .isEqualTo(ESC + "[3;2~");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.HOME, true, false, false, false, false, base)))
            .isEqualTo(ESC + "[1;2H");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.F5, false, true, true, false, false, base)))
            .isEqualTo(ESC + "[15;7~");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.UP, true, true, true, false, false, base)))
            .isEqualTo(ESC + "[1;8A");
    }

    @Test
    public void metaChordsAreShortcutsNotInput() {
        IntFunction<byte[]> base = base(new TerminalKeyEncoder(Platform.macOS));
        for (KeyCode code : new KeyCode[] {KeyCode.UP, KeyCode.LEFT, KeyCode.TAB, KeyCode.PAGE_DOWN, KeyCode.F3}) {
            assertWithMessage("Meta+" + code)
                .that(TerminalNavigationKeys.encode(code, false, false, false, true, true, base)).isNull();
            assertWithMessage("Shift+Meta+" + code)
                .that(TerminalNavigationKeys.encode(code, true, false, false, true, true, base)).isNull();
        }
    }

    @Test
    public void onlyNavigationKeysAreEncoded() {
        IntFunction<byte[]> base = base(new TerminalKeyEncoder(Platform.Linux));

        assertThat(TerminalNavigationKeys.isNavigationKey(KeyCode.ENTER)).isFalse();
        assertThat(TerminalNavigationKeys.isNavigationKey(KeyCode.A)).isFalse();
        assertThat(TerminalNavigationKeys.isNavigationKey(KeyCode.F12)).isTrue();
        assertThat(TerminalNavigationKeys.encode(KeyCode.ENTER, false, false, false, false, false, base)).isNull();
        assertThat(TerminalNavigationKeys.encode(KeyCode.BACK_SPACE, true, false, false, false, false, base)).isNull();
    }

    @Test
    public void onlyXtermAndDecEmulationsAreEncodedTheOthersKeepTheirFixedSequences() {
        assertThat(TerminalNavigationKeys.isKorttyEncoded(EmulationType.XTERM)).isTrue();
        assertThat(TerminalNavigationKeys.isKorttyEncoded(EmulationType.VT220)).isTrue();
        assertThat(TerminalNavigationKeys.isKorttyEncoded(EmulationType.WY60)).isFalse();
        assertThat(TerminalNavigationKeys.isKorttyEncoded(EmulationType.TN3270)).isFalse();
        assertThat(TerminalNavigationKeys.isKorttyEncoded(null)).isFalse();

        // The bytes korTTY sent for every emulation before; unchanged for the ones not encoded.
        assertThat(text(TerminalNavigationKeys.legacySequence(KeyCode.UP))).isEqualTo(ESC + "OA");
        assertThat(text(TerminalNavigationKeys.legacySequence(KeyCode.TAB))).isEqualTo("\t");
        assertThat(text(TerminalNavigationKeys.legacySequence(KeyCode.HOME))).isEqualTo(ESC + "[H");
        assertThat(text(TerminalNavigationKeys.legacySequence(KeyCode.F12))).isEqualTo(ESC + "[24~");
        assertThat(TerminalNavigationKeys.legacySequence(KeyCode.ENTER)).isNull();
    }

    @Test
    public void korttysPromptJumpsKeepTheirKeyInEveryEmulationShellIntegrationReads() {
        for (EmulationType emulation : EmulationType.values()) {
            if (ShellIntegrationTtyConnector.appliesTo(emulation)) {
                assertWithMessage("korTTY's prompt jump in a %s pane, which has prompt marks", emulation)
                    .that(TerminalNavigationKeys.mayKeepKeyLocal(emulation, true)).isTrue();
            }
            if (TerminalNavigationKeys.isKorttyEncoded(emulation)) {
                assertWithMessage("SithTermFX's scrollback keys in a %s pane", emulation)
                    .that(TerminalNavigationKeys.mayKeepKeyLocal(emulation, false)).isTrue();
            }
        }
        // SCO ANSI is read by shell integration but keeps the fixed key sequences: only korTTY's own
        // action may keep Cmd/Ctrl+Shift+Up there, and Shift+Page Up still reaches the program.
        assertThat(ShellIntegrationTtyConnector.appliesTo(EmulationType.SCOANSI)).isTrue();
        assertThat(TerminalNavigationKeys.isKorttyEncoded(EmulationType.SCOANSI)).isFalse();
        assertThat(TerminalNavigationKeys.mayKeepKeyLocal(EmulationType.SCOANSI, true)).isTrue();
        assertThat(TerminalNavigationKeys.mayKeepKeyLocal(EmulationType.SCOANSI, false)).isFalse();
        assertThat(TerminalNavigationKeys.mayKeepKeyLocal(EmulationType.WY60, false)).isFalse();
        assertThat(TerminalNavigationKeys.mayKeepKeyLocal(null, false)).isFalse();
    }

    @Test
    public void mosh4jGetsSs3ArrowsUntilItsCursorKeyModeIsVerified() {
        // mosh4j re-renders the server's screen locally; whether ESC[?1h reaches korTTY's emulator is
        // not verified. mosh-server turns SS3 arrows into whatever the application asked for, so a
        // mosh4j pane keeps sending them, as before, even while the local emulator is in normal mode.
        assertThat(TerminalNavigationKeys.prefersSs3Arrows(
            new Mosh4jTtyConnector(new ServerConnection("Test", "example.com", 22, "daniel"), "secret"))).isTrue();
        assertThat(TerminalNavigationKeys.prefersSs3Arrows(null)).isFalse();

        TerminalKeyEncoder normalMode = new TerminalKeyEncoder(Platform.Linux);
        IntFunction<byte[]> mosh = TerminalNavigationKeys.ss3ArrowBase(base(normalMode));

        assertThat(text(TerminalNavigationKeys.encode(KeyCode.UP, false, false, false, false, false, mosh)))
            .isEqualTo(ESC + "OA");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.DOWN, false, false, false, false, false, mosh)))
            .isEqualTo(ESC + "OB");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.RIGHT, false, false, false, false, false, mosh)))
            .isEqualTo(ESC + "OC");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.LEFT, false, false, false, false, false, mosh)))
            .isEqualTo(ESC + "OD");
        // Modified arrows still carry their modifier; other keys are untouched.
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.LEFT, false, true, false, false, false, mosh)))
            .isEqualTo(ESC + "[1;5D");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.HOME, false, false, false, false, false, mosh)))
            .isEqualTo(ESC + "[H");
        assertThat(text(TerminalNavigationKeys.encode(KeyCode.F1, false, false, false, false, false, mosh)))
            .isEqualTo(ESC + "OP");
    }

    private static String plain(KeyCode code, TerminalKeyEncoder encoder) {
        return text(TerminalNavigationKeys.encode(code, false, false, false, false, false, base(encoder)));
    }

    private static IntFunction<byte[]> base(TerminalKeyEncoder encoder) {
        return vk -> encoder.getCode(vk, 0);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
    }
}
