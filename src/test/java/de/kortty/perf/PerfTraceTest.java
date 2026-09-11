package de.kortty.perf;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;

/**
 * The trace is off in the test JVM (no {@code kortty.ui.perf}, no {@code KORTTY_UI_PERF}), so this
 * pins the free-when-off contract: one shared no-op span, and every call is a harmless no-op.
 */
public class PerfTraceTest {

    @Test
    public void disabledByDefaultAndSharesOneNoOpSpan() {
        assertFalse(PerfTrace.ENABLED, "perf tracing must be opt-in");
        PerfTrace.Span first = PerfTrace.begin("a");
        PerfTrace.Span second = PerfTrace.begin("b");
        assertNotNull(first);
        assertSame(first, second, "disabled tracing must not allocate a span per call");
        assertSame(first.mark("x"), first);
        first.end();
        PerfTrace.logDuration("a", 1_000_000L);
        PerfTrace.log("line");
    }

    @Test
    public void millisFormatsWithOneDecimalAndRootLocale() {
        assertEquals(PerfTrace.millis(1_234_567L), "1.2ms");
        assertEquals(PerfTrace.millis(0L), "0.0ms");
    }
}
