package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;
import org.testng.annotations.Test;

/** The highlight event names a set by its class only: never a user set's id, name or patterns. */
class HighlightTelemetryTest {

    @Test
    void builtInSetsAreReportedByTheirStableId() {
        for (String id : HighlightBuiltinSets.IDS) {
            assertThat(HighlightTelemetry.setClass(id)).isEqualTo(id);
        }
    }

    @Test
    void everyUserSetIsJustCustom() {
        assertThat(HighlightTelemetry.setClass("2f9c0f3e-my-production-servers")).isEqualTo(HighlightTelemetry.SET_CUSTOM);
        // A reserved but unknown id is not a built-in either.
        assertThat(HighlightTelemetry.setClass("builtin.something-new")).isEqualTo(HighlightTelemetry.SET_CUSTOM);
    }

    @Test
    void noSetIsNone() {
        assertThat(HighlightTelemetry.setClass(null)).isEqualTo("none");
        assertThat(HighlightTelemetry.setClass(" ")).isEqualTo("none");
        assertThat(HighlightTelemetry.setClass(TerminalHighlightService.NONE_ID)).isEqualTo("none");
    }

    @Test
    void propsCarryTheSetClassAndTheSourceOnly() {
        Map<String, Object> props = HighlightTelemetry.props("user-set", HighlightTelemetry.SOURCE_SHORTCUT);

        assertThat(props).containsExactly(
            HighlightTelemetry.PROP_SET, HighlightTelemetry.SET_CUSTOM,
            HighlightTelemetry.PROP_SOURCE, "shortcut");
        assertThat(HighlightTelemetry.props(HighlightBuiltinSets.NETWORK, HighlightTelemetry.SOURCE_MENU))
            .containsExactly("set", "builtin.network", "source", "menu");
    }

    @Test
    void anInheritedSetReportsWhetherTheConnectionOrTheDefaultDecided() {
        assertThat(HighlightTelemetry.inheritedSource(TerminalHighlightService.Level.CONNECTION))
            .isEqualTo(HighlightTelemetry.SOURCE_CONNECTION);
        assertThat(HighlightTelemetry.inheritedSource(TerminalHighlightService.Level.DEFAULT))
            .isEqualTo(HighlightTelemetry.SOURCE_DEFAULT);
        assertThat(HighlightTelemetry.inheritedSource(TerminalHighlightService.Level.NONE))
            .isEqualTo(HighlightTelemetry.SOURCE_DEFAULT);
        assertThat(HighlightTelemetry.SOURCE_CONNECTION).isEqualTo("connection");
    }
}
