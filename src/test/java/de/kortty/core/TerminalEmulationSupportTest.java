package de.kortty.core;

import com.sithtermfx.core.emulator.EmulationType;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.Arrays;

import static com.google.common.truth.Truth.assertThat;

class TerminalEmulationSupportTest {

    @Test
    void availableEmulationsComeFromSithTermFx() {
        assertThat(TerminalEmulationSupport.availableEmulations())
                .containsExactlyElementsIn(Arrays.asList(EmulationType.values()))
                .inOrder();
    }

    @Test
    void defaultsToXtermForMissingOrInvalidValues() {
        assertThat(TerminalEmulationSupport.fromStoredValue(null)).isEqualTo(EmulationType.XTERM);
        assertThat(TerminalEmulationSupport.fromStoredValue("")).isEqualTo(EmulationType.XTERM);
        assertThat(TerminalEmulationSupport.fromStoredValue("not-a-terminal")).isEqualTo(EmulationType.XTERM);
    }

    @Test
    void mapsEnumNameTermNameAndDisplayLabel() {
        assertThat(TerminalEmulationSupport.fromStoredValue("VT220")).isEqualTo(EmulationType.VT220);
        assertThat(TerminalEmulationSupport.fromStoredValue("xterm-256color")).isEqualTo(EmulationType.XTERM);
        assertThat(TerminalEmulationSupport.fromStoredValue("XTerm (256 color) (xterm-256color)"))
                .isEqualTo(EmulationType.XTERM);
    }

    @Test
    void searchMatchesNameDisplayNameAndTermName() {
        assertThat(TerminalEmulationSupport.matchesSearch(EmulationType.VT220, "vt")).isTrue();
        assertThat(TerminalEmulationSupport.matchesSearch(EmulationType.WY60, "wy")).isTrue();
        assertThat(TerminalEmulationSupport.matchesSearch(EmulationType.HP700_92, "hp700")).isTrue();
        assertThat(TerminalEmulationSupport.matchesSearch(EmulationType.XTERM, "xterm-256")).isTrue();
        assertThat(TerminalEmulationSupport.matchesSearch(EmulationType.XTERM, "wy")).isFalse();
    }

    @Test
    void connectionTermNameUsesSelectedEmulation() {
        ServerConnection connection = new ServerConnection();
        connection.setTerminalEmulationType("VT100");

        assertThat(TerminalEmulationSupport.termName(connection)).isEqualTo("vt100");
    }

    @Test
    void connectionTermNameDefaultsToXterm256Color() {
        assertThat(TerminalEmulationSupport.termName(new ServerConnection())).isEqualTo("xterm-256color");
    }
}
