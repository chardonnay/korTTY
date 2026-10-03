package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.emulator.EmulationType;
import com.sithtermfx.core.emulator.Emulator;
import com.sithtermfx.core.emulator.EmulatorFactory;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * What korTTY relies on in SithTermFX's handling of the bell: a BEL reaches the terminal display,
 * which in a pane is korTTY's panel ({@code KorttyTerminalPanel.beep()}), exactly once per BEL in the
 * output, and a BEL that ends an OSC sequence does not ring. Pinned against the emulators of the
 * SithTermFX version korTTY ships, so a version bump that changes it fails here.
 */
class SithTermFxOscContractTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";

    /** Block-mode protocols whose alarm is part of the data stream, not a BEL character. */
    private static final Set<EmulationType> BLOCK_MODE = EnumSet.of(EmulationType.TN3270, EmulationType.TN5250);

    @Test
    void aLoneBelRingsTheDisplayOnce() throws IOException {
        Screen screen = new Screen(40, 5, 10);
        screen.interpret(BEL);
        assertThat(screen.display.bells).isEqualTo(1);
    }

    @Test
    void everyBelInTheOutputRingsOnce() throws IOException {
        Screen screen = new Screen(40, 5, 10);
        screen.interpret("make: *** [all] Error 2" + BEL + "\r\n" + BEL + BEL + "done");
        assertWithMessage("korTTY's listener counts on one call per BEL; it coalesces them itself")
            .that(screen.display.bells).isEqualTo(3);
    }

    @Test
    void aBelThatEndsATitleSequenceDoesNotRing() throws IOException {
        Screen screen = new Screen(40, 5, 10);
        screen.interpret(ESC + "]0;user@host: ~" + BEL + "prompt$ ");
        assertThat(screen.display.bells).isEqualTo(0);
        assertThat(screen.titles).containsExactly("user@host: ~");
    }

    @Test
    void aBelThatEndsAnUnhandledOscDoesNotRing() throws IOException {
        Screen screen = new Screen(40, 5, 10);
        screen.interpret(ESC + "]133;A" + BEL + ESC + "]9;build done" + BEL + ESC + "]7;file://host/tmp" + BEL + "$ ");
        assertThat(screen.display.bells).isEqualTo(0);
    }

    @DataProvider
    Object[][] chunkSizes() {
        return new Object[][] {{1}, {2}, {3}, {7}, {1024}};
    }

    @Test(dataProvider = "chunkSizes")
    void throughTheShellIntegrationWrapperOnlyTheLoneBelRings(int chunkSize) throws IOException {
        // The wrapper takes OSC 133 and OSC 9 out of the stream; the BELs that end them go with them.
        Screen screen = new Screen(40, 5, 10);
        List<ShellIntegrationEvent> events = new ArrayList<>();
        String output = ESC + "]133;A" + BEL + "$ " + ESC + "]133;B" + BEL + "ls" + BEL + "\r\n"
            + ESC + "]9;build done" + BEL + ESC + "]0;title" + BEL;
        screen.run(new ShellIntegrationTtyConnector(ScriptedConnector.inChunksOf(output, chunkSize), events::add));

        assertThat(screen.display.bells).isEqualTo(1);
        assertThat(events).hasSize(3);
    }

    @Test
    void aBelReachesTheDisplayInEveryCharacterEmulation() throws IOException {
        // The bell needs no shell integration: emulations without the OSC wrapper ring the same way.
        for (EmulationType type : EmulationType.values()) {
            if (BLOCK_MODE.contains(type)) {
                continue;
            }
            Screen screen = new Screen(80, 24, 10);
            Emulator emulator = EmulatorFactory.createEmulator(type, new ArrayTerminalDataStream(BEL.toCharArray()),
                screen.terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
            assertWithMessage(type.name()).that(screen.display.bells).isEqualTo(1);
        }
    }
}
