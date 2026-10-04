package de.kortty.telemetry;

import de.kortty.model.SessionRestoreMode;
import de.kortty.ui.MultiExecMembership;
import de.kortty.ui.actions.PaletteEntry;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The props of the terminal-UX usage events are booleans, coarse numbers or ids from a fixed set:
 * never a query, a host, a title, an action id or an exact count.
 */
class TerminalUxTelemetryTest {

    private static final Set<Integer> BUCKETS = Set.of(0, 1, 2, 3, 5, 10, 20);
    private static final Set<String> KINDS = Set.of("action", "tab", "connection", "snippet", "unknown");
    private static final Set<String> MODES = Set.of("ask", "auto", "off");
    private static final Set<String> TRIGGERS = Set.of("menu", "offer", "auto");

    @Test
    void commandPaletteUsedSendsTheKindAndWhetherTheQueryWasScopedOnly() {
        for (PaletteEntry.Kind kind : PaletteEntry.Kind.values()) {
            for (boolean scoped : new boolean[] {true, false}) {
                Map<String, Object> props = TerminalUxTelemetry.commandPaletteUsed(kind, scoped);
                assertThat(props.keySet()).containsExactly("kind", "scoped").inOrder();
                assertThat(KINDS).contains(props.get("kind"));
                assertThat(props.get("scoped")).isEqualTo(scoped);
                assertOnlyEnumNumberOrBoolean(props);
            }
        }
        assertThat(TerminalUxTelemetry.commandPaletteUsed(PaletteEntry.Kind.CONNECTION, true))
            .containsExactly("kind", "connection", "scoped", true);
        assertThat(TerminalUxTelemetry.commandPaletteUsed(null, false).get("kind")).isEqualTo("unknown");
    }

    @Test
    void multiExecChangedSendsWhetherItIsOnAndBucketedReach() {
        Map<String, Object> props = TerminalUxTelemetry.multiExecChanged(true, new MultiExecMembership.Counts(7, 4, 2));
        assertThat(props).containsExactly("enabled", true, "panes", 5, "tabs", 3, "windows", 2).inOrder();
        assertOnlyEnumNumberOrBoolean(props);

        Map<String, Object> off = TerminalUxTelemetry.multiExecChanged(false, null);
        assertThat(off).containsExactly("enabled", false, "panes", 0, "tabs", 0, "windows", 0).inOrder();
    }

    @Test
    void sessionRestoredSendsModeTriggerAndBucketedCounts() {
        for (SessionRestoreMode mode : SessionRestoreMode.values()) {
            for (TerminalUxTelemetry.RestoreTrigger trigger : TerminalUxTelemetry.RestoreTrigger.values()) {
                Map<String, Object> props = TerminalUxTelemetry.sessionRestored(mode, trigger, 3, 41);
                assertThat(props.keySet()).containsExactly("mode", "trigger", "windows", "tabs").inOrder();
                assertThat(MODES).contains(props.get("mode"));
                assertThat(TRIGGERS).contains(props.get("trigger"));
                assertThat(props.get("windows")).isEqualTo(3);
                assertThat(props.get("tabs")).isEqualTo(20);
                assertOnlyEnumNumberOrBoolean(props);
            }
        }
        Map<String, Object> fallback = TerminalUxTelemetry.sessionRestored(null, null, 1, 1);
        assertThat(fallback.get("mode")).isEqualTo(SessionRestoreMode.DEFAULT.id());
        assertThat(fallback.get("trigger")).isEqualTo("menu");
    }

    @Test
    void countsAreRoundedDownToCoarseBuckets() {
        assertThat(TerminalUxTelemetry.countBucket(-4)).isEqualTo(0);
        assertThat(TerminalUxTelemetry.countBucket(0)).isEqualTo(0);
        assertThat(TerminalUxTelemetry.countBucket(1)).isEqualTo(1);
        assertThat(TerminalUxTelemetry.countBucket(2)).isEqualTo(2);
        assertThat(TerminalUxTelemetry.countBucket(4)).isEqualTo(3);
        assertThat(TerminalUxTelemetry.countBucket(9)).isEqualTo(5);
        assertThat(TerminalUxTelemetry.countBucket(19)).isEqualTo(10);
        assertThat(TerminalUxTelemetry.countBucket(Integer.MAX_VALUE)).isEqualTo(20);
        for (int count = -2; count < 200; count++) {
            assertThat(BUCKETS).contains(TerminalUxTelemetry.countBucket(count));
        }
    }

    @Test
    void theNewEventNamesAreSnakeCaseAndEveryEventNameIsUnique() throws IllegalAccessException {
        assertThat(TelemetryEvents.COMMAND_PALETTE_USED).isEqualTo("command_palette_used");
        assertThat(TelemetryEvents.MULTI_EXEC_CHANGED).isEqualTo("multi_exec_changed");
        assertThat(TelemetryEvents.SESSION_RESTORED).isEqualTo("session_restored");
        assertThat(TelemetryEvents.TERMINAL_HIGHLIGHT_APPLIED).isEqualTo("terminal_highlight_applied");

        Pattern snakeCase = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
        Set<String> names = new HashSet<>();
        for (Field field : TelemetryEvents.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getName().equals("GUIDE_LOCATION")) {
                continue;
            }
            String name = (String) field.get(null);
            assertWithMessage(field.getName()).that(snakeCase.matcher(name).matches()).isTrue();
            assertWithMessage("duplicate event name " + name).that(names.add(name)).isTrue();
        }
    }

    /** Booleans, numbers and short lowercase ids only, as the anti-PII contract of the events asks. */
    private static void assertOnlyEnumNumberOrBoolean(Map<String, Object> props) {
        Pattern enumId = Pattern.compile("[a-z][a-z_]*");
        for (Map.Entry<String, Object> prop : props.entrySet()) {
            Object value = prop.getValue();
            if (value instanceof String text) {
                assertWithMessage(prop.getKey()).that(enumId.matcher(text).matches()).isTrue();
            } else {
                assertWithMessage(prop.getKey()).that(value instanceof Boolean || value instanceof Integer).isTrue();
            }
            if (value instanceof Integer number) {
                assertWithMessage(prop.getKey() + " is bucketed").that(BUCKETS).contains(number);
            }
        }
    }
}
