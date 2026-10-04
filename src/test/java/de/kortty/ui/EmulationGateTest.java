package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.emulator.EmulationType;
import com.sithtermfx.core.emulator.Emulator;
import com.sithtermfx.core.emulator.EmulatorFactory;
import com.sithtermfx.core.emulator.SithEmulator;
import de.kortty.ui.OscEmulatorHarness.Screen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * The OSC splitter mirrors SithEmulator's reader, so only emulations SithTermFX runs on SithEmulator
 * may get {@link ShellIntegrationTtyConnector}. Others give the same bytes other meanings (PETSCII
 * 0x9D moves the cursor left), and splitting their output would corrupt the screen.
 */
class EmulationGateTest {

    private static final Set<EmulationType> SITH_EMULATOR_FAMILY = EnumSet.of(
        EmulationType.XTERM,
        EmulationType.VT100, EmulationType.VT102, EmulationType.VT220,
        EmulationType.VT320, EmulationType.VT420, EmulationType.VT520,
        EmulationType.SCOANSI, EmulationType.SUN_CDE, EmulationType.CTERM);

    @Test
    void theWrapperIsForTheSithEmulatorFamilyOnly() {
        for (EmulationType type : EmulationType.values()) {
            assertWithMessage(type.name())
                .that(ShellIntegrationTtyConnector.appliesTo(type))
                .isEqualTo(SITH_EMULATOR_FAMILY.contains(type));
        }
        for (EmulationType other : EnumSet.of(EmulationType.WY50, EmulationType.WY60, EmulationType.WY160,
                EmulationType.TVI910, EmulationType.TVI920, EmulationType.TVI925, EmulationType.HP2392,
                EmulationType.HP700_92, EmulationType.TN3270, EmulationType.TN5250, EmulationType.PETSCII)) {
            assertWithMessage(other.name()).that(ShellIntegrationTtyConnector.appliesTo(other)).isFalse();
        }
        assertThat(ShellIntegrationTtyConnector.appliesTo(null)).isFalse();
    }

    @Test
    void theGateMatchesTheEmulatorsSithTermFxCreates() {
        // Catches a SithTermFX bump that moves an emulation onto, or off, SithEmulator.
        for (EmulationType type : EmulationType.values()) {
            Screen screen = new Screen(80, 24, 10);
            Emulator emulator = EmulatorFactory.createEmulator(type, new ArrayTerminalDataStream(new char[0]), screen.terminal);
            assertWithMessage(type.name())
                .that(ShellIntegrationTtyConnector.appliesTo(type))
                .isEqualTo(emulator instanceof SithEmulator);
        }
    }

    @Test
    void thePaneDecoratorInstallsTheWrapperOutermostBehindTheGate() throws IOException {
        // TerminalView needs a JavaFX stage; pin its wiring in the source instead.
        // Windows CI checks the sources out with CRLF line endings.
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        String decorate = methodBody(view, "private TtyConnector decorateTerminalConnector(");
        String gate = methodBody(view, "private TtyConnector withShellIntegration(");

        int emulation = decorate.indexOf("applyTerminalEmulation(widget, baseConnector);");
        int wrap = decorate.indexOf("return withShellIntegration(widget, new TerminalColorFilteringTtyConnector(");
        assertWithMessage("the emulation is set before the gate reads it").that(emulation).isAtLeast(0);
        assertWithMessage("the colour filter is wrapped, so the splitter sees what the emulator reads")
            .that(wrap).isGreaterThan(emulation);
        assertWithMessage("every decorated chain goes through the gate")
            .that(decorate.split("return ", -1).length - 1).isEqualTo(2); // the null guard and the chain
        assertThat(gate).contains("ShellIntegrationTtyConnector.appliesTo(widget.getEmulationType())");
        assertThat(gate).contains("new ShellIntegrationTtyConnector(decorated, event -> onShellIntegrationEvent(widget, event))");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces in " + signature);
    }
}
