package de.kortty.ui;

import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link PaneOrigin} of each pane of one terminal tab, so a "same server" split opens on the
 * server of the pane it splits.
 *
 * <p>A split builds and connects its connector before the split pane creates the new pane, so the
 * origin is first recorded against that connector ({@link #expect}) and moves to the pane when the
 * pane is bound to it ({@link #bind}, from the tab's connector decorator). Both maps are keyed by
 * identity, and the connector key is always the raw base connector, never one of korTTY's wrappers:
 * a wrapper is rebuilt every time the pane is decorated again (a Mosh recovery, an effect), while the
 * base connector is the session.
 *
 * <p>Only panes whose origin differs from the tab's are recorded. Every other pane, starting with
 * the tab's first one, resolves to the tab's origin as it is at that moment.
 *
 * <p>Generic in the pane and connector types, so it carries no JavaFX or terminal dependency.
 * Thread-safe: splits are usually made on the JavaFX thread, but a connector may be built off it.
 *
 * @param <P> the pane type
 * @param <C> the connector type
 */
final class PaneOrigins<P, C> {

    private final Map<P, PaneOrigin> byPane = new IdentityHashMap<>();

    private final Map<C, PaneOrigin> byConnector = new IdentityHashMap<>();

    /**
     * The origin recorded for {@code pane}, or null when it runs the tab's (its first pane, a pane
     * split from such a pane on the same server, or an unknown pane).
     */
    synchronized @Nullable PaneOrigin recorded(@Nullable P pane) {
        return pane != null ? byPane.get(pane) : null;
    }

    /** The origin {@code pane} runs: its recorded one, or {@code tab} when it has none. */
    synchronized PaneOrigin resolve(@Nullable P pane, PaneOrigin tab) {
        return PaneOrigin.resolve(recorded(pane), tab);
    }

    /**
     * Records that {@code connector}, built for a new pane, runs {@code origin}. A null origin
     * stands for the tab's and records nothing, so the new pane follows the tab.
     */
    synchronized void expect(C connector, @Nullable PaneOrigin origin) {
        Objects.requireNonNull(connector, "connector");
        if (origin == null) {
            byConnector.remove(connector);
        } else {
            byConnector.put(connector, origin);
        }
    }

    /**
     * Binds {@code pane} to {@code baseConnector}: the origin expected for that connector becomes the
     * pane's. A connector nothing was expected for leaves the pane's origin as it was, so binding the
     * same session again (a Mosh recovery re-decorates a live pane) changes nothing.
     */
    synchronized void bind(@Nullable P pane, @Nullable C baseConnector) {
        if (pane == null || baseConnector == null) {
            return;
        }
        PaneOrigin expected = byConnector.remove(baseConnector);
        if (expected != null) {
            byPane.put(pane, expected);
        }
    }

    /** Forgets a closed pane. */
    synchronized void forget(@Nullable P pane) {
        if (pane != null) {
            byPane.remove(pane);
        }
    }

    /** The number of panes with an origin of their own; for tests. */
    synchronized int recordedPaneCount() {
        return byPane.size();
    }

    /** The number of connectors whose origin waits for a pane; for tests. */
    synchronized int pendingConnectorCount() {
        return byConnector.size();
    }
}
