package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.emulator.EmulationType;
import com.sithtermfx.core.input.KeyEvent;
import de.kortty.core.Mosh4jTtyConnector;
import javafx.scene.input.KeyCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Encodes the terminal's navigation keys (Tab, the arrows, Home/End, Page Up/Down, Insert/Delete
 * and F1-F12) the way xterm does, including modifiers and the terminal's cursor-key mode.
 *
 * <p>korTTY routes these keys itself, ahead of the emulator's own key handling, so they still reach
 * the shell when the focus is on the pane or its scroll bar rather than on the terminal canvas, and
 * so broadcast mode can mirror them to every pane. SithTermFX's own encoder cannot be used for the
 * modifiers: its key panel passes AWT {@code *_DOWN_MASK} values where the encoder checks its own
 * masks (so Ctrl+Left arrives as plain Left and Shift+Tab as Tab), and it rewrites its stored
 * sequences in place when it adds a modifier, which would corrupt every later plain key. This class
 * only asks the terminal for the unmodified sequence, which follows DECCKM (application cursor keys,
 * {@code ESC[?1h}) and the keypad mode, and applies the xterm modifier rules to a copy of it.
 *
 * <p>Free of the JavaFX toolkit (only the {@link KeyCode} enum is used), so it is unit-tested
 * without a display. Drop the modifier logic here once SithTermFX encodes modifiers correctly.
 */
public final class TerminalNavigationKeys {

    private static final byte ESC = 0x1B;

    private TerminalNavigationKeys() {
    }

    /** True for the keys this class encodes: Tab, arrows, Home/End, Page Up/Down, Insert/Delete, F1-F12. */
    public static boolean isNavigationKey(@Nullable KeyCode code) {
        if (code == null) {
            return false;
        }
        return switch (code) {
            case TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
                 F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 -> true;
            default -> false;
        };
    }

    /**
     * True for the emulations whose keyboard follows xterm/DEC conventions, which {@link #encode}
     * implements. Every other emulation (Wyse, TeleVideo, HP, SCO ANSI, IBM 3270/5250, PETSCII)
     * keeps the fixed sequences of {@link #legacySequence}, byte for byte what korTTY sent before.
     */
    public static boolean isKorttyEncoded(@Nullable EmulationType emulationType) {
        if (emulationType == null) {
            return false;
        }
        return switch (emulationType) {
            case XTERM, VT100, VT102, VT220, VT320, VT420, VT520, SUN_CDE, CTERM -> true;
            default -> false;
        };
    }

    /**
     * Whether the pane's first key action that matches a navigation key may keep the key from the
     * program, when it can run now ({@code TerminalSplitPane.routeKeyPressed}).
     *
     * <ul>
     *   <li>In an emulation korTTY encodes ({@link #isKorttyEncoded}) every action may, SithTermFX's
     *       scrollback keys included.</li>
     *   <li>The other emulations keep the fixed sequences of {@link #legacySequence}, and with them
     *       the program gets SithTermFX's scrollback keys, as before. korTTY's own actions still come
     *       first there: shell integration reads the prompt marks of a SCO ANSI pane too
     *       ({@code ShellIntegrationTtyConnector.appliesTo}), and its prompt jumps can run only while
     *       the pane has them.</li>
     * </ul>
     *
     * @param korttysOwnAction whether the action is one of korTTY's own key actions for the pane
     *                         ({@code KorttyTermWidget.setLeadingTerminalActions})
     */
    public static boolean mayKeepKeyLocal(@Nullable EmulationType emulationType, boolean korttysOwnAction) {
        return korttysOwnAction || isKorttyEncoded(emulationType);
    }

    /**
     * Encodes a navigation key with its modifiers.
     *
     * <ul>
     *   <li>Any chord with Meta (Cmd on macOS) returns {@code null}: it is a shortcut, not input.</li>
     *   <li>Tab sends TAB, Shift+Tab sends {@code ESC[Z}; Tab with Ctrl or Alt returns {@code null},
     *       because Ctrl+Tab and Ctrl+Shift+Tab switch the tabs.</li>
     *   <li>Without modifiers the terminal's own sequence is sent, so DECCKM is honoured.</li>
     *   <li>On macOS Option+Left/Right sends {@code ESC b} / {@code ESC f}, the word movement of
     *       Terminal.app and of SithTermFX's own key map.</li>
     *   <li>Otherwise the xterm PC-style rule applies with {@code m = 1 + Shift + 2*Alt + 4*Ctrl}:
     *       {@code ESC O X} and {@code ESC [ X} become {@code ESC [ 1 ; m X}, and
     *       {@code ESC [ n ~} becomes {@code ESC [ n ; m ~}.</li>
     * </ul>
     *
     * @param base the terminal's unmodified sequence per SithTermFX key code (AWT-compatible, as
     *             {@link KeyCode#getCode()} returns it), for example
     *             {@code vk -> terminal.getCodeForKey(vk, 0)}. The returned array is never modified.
     * @return a new array, or {@code null} when the key must not be sent as terminal input
     */
    public static byte @Nullable [] encode(@Nullable KeyCode code, boolean shift, boolean ctrl, boolean alt,
                                           boolean meta, boolean macOs, @NotNull IntFunction<byte[]> base) {
        if (!isNavigationKey(code) || meta) {
            return null;
        }
        if (code == KeyCode.TAB) {
            if (ctrl || alt) {
                return null;
            }
            return shift ? new byte[] {ESC, '[', 'Z'} : new byte[] {'\t'};
        }
        byte[] plain = base.apply(code.getCode());
        if (plain == null || plain.length == 0) {
            return null;
        }
        if (!shift && !ctrl && !alt) {
            return plain.clone();
        }
        if (macOs && alt && !shift && !ctrl && (code == KeyCode.LEFT || code == KeyCode.RIGHT)) {
            return new byte[] {ESC, (byte) (code == KeyCode.LEFT ? 'b' : 'f')};
        }
        int modifier = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        return withModifier(plain, modifier);
    }

    /**
     * Wraps a base so the four arrows always come as SS3 ({@code ESC O A} ...), whatever the cursor
     * key mode. Used for mosh4j connections: they re-render the server's screen locally and it is not
     * verified that the application-cursor mode reaches korTTY's emulator, while mosh-server turns
     * SS3 arrows into what the remote application asked for. Modified arrows still get the xterm
     * modifier form ({@code ESC [ 1 ; m A}).
     */
    public static @NotNull IntFunction<byte[]> ss3ArrowBase(@NotNull IntFunction<byte[]> base) {
        return vk -> {
            byte finalByte = switch (vk) {
                case KeyEvent.VK_UP -> 'A';
                case KeyEvent.VK_DOWN -> 'B';
                case KeyEvent.VK_RIGHT -> 'C';
                case KeyEvent.VK_LEFT -> 'D';
                default -> 0;
            };
            return finalByte != 0 ? new byte[] {ESC, 'O', finalByte} : base.apply(vk);
        };
    }

    /**
     * True when plain arrows must be sent as SS3 for this connector (see {@link #ss3ArrowBase}).
     *
     * @param connector the pane's connector with korTTY's decorators (colour filter, terminal
     *                  effects) already unwrapped
     */
    public static boolean prefersSs3Arrows(@Nullable TtyConnector connector) {
        return connector instanceof Mosh4jTtyConnector;
    }

    /**
     * The fixed sequences korTTY sent for every emulation before the keys were encoded per mode.
     * Modifiers are ignored. Kept for the emulations {@link #isKorttyEncoded} does not cover, so
     * their keyboard stays exactly as it was.
     *
     * @return a new array, or {@code null} for keys that are not navigation keys
     */
    public static byte @Nullable [] legacySequence(@Nullable KeyCode code) {
        if (!isNavigationKey(code)) {
            return null;
        }
        String sequence = switch (code) {
            case TAB -> "\t";
            case UP -> "\u001BOA";
            case DOWN -> "\u001BOB";
            case RIGHT -> "\u001BOC";
            case LEFT -> "\u001BOD";
            case HOME -> "\u001B[H";
            case END -> "\u001B[F";
            case PAGE_UP -> "\u001B[5~";
            case PAGE_DOWN -> "\u001B[6~";
            case INSERT -> "\u001B[2~";
            case DELETE -> "\u001B[3~";
            case F1 -> "\u001BOP";
            case F2 -> "\u001BOQ";
            case F3 -> "\u001BOR";
            case F4 -> "\u001BOS";
            case F5 -> "\u001B[15~";
            case F6 -> "\u001B[17~";
            case F7 -> "\u001B[18~";
            case F8 -> "\u001B[19~";
            case F9 -> "\u001B[20~";
            case F10 -> "\u001B[21~";
            case F11 -> "\u001B[23~";
            case F12 -> "\u001B[24~";
            default -> throw new IllegalStateException("unhandled navigation key " + code);
        };
        return sequence.getBytes(StandardCharsets.US_ASCII);
    }

    /** Applies the xterm PC-style modifier parameter to a copy of an unmodified sequence. */
    private static byte @NotNull [] withModifier(byte @NotNull [] plain, int modifier) {
        byte[] parameter = (";" + modifier).getBytes(StandardCharsets.US_ASCII);
        int last = plain.length - 1;
        // ESC O X (SS3) or ESC [ X: CSI with the default parameter 1, then the modifier.
        if (plain.length == 3 && plain[0] == ESC && (plain[1] == 'O' || plain[1] == '[')) {
            byte[] result = new byte[3 + 1 + parameter.length];
            result[0] = ESC;
            result[1] = '[';
            result[2] = '1';
            System.arraycopy(parameter, 0, result, 3, parameter.length);
            result[result.length - 1] = plain[last];
            return result;
        }
        // ESC [ n ~ (and any other CSI sequence): insert the modifier before the final byte.
        if (plain.length > 3 && plain[0] == ESC && plain[1] == '[') {
            byte[] result = Arrays.copyOf(plain, plain.length + parameter.length);
            System.arraycopy(parameter, 0, result, last, parameter.length);
            result[result.length - 1] = plain[last];
            return result;
        }
        // A shape the xterm rules do not cover: send the key without its modifiers.
        return plain.clone();
    }
}
