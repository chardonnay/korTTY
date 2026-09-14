package de.kortty.codingagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * "Next blocked agent" navigation across windows: a ring over the BLOCKED entries in display order
 * starting after the pane the user is on, focused through the injected {@link PaneFocuser}
 * (implemented by the UI bridge over MainWindow/TerminalView, faked in tests).
 */
public final class CodingAgentNavigator {

    /** Brings a pane to the front and reports the pane the user is currently on. */
    public interface PaneFocuser {
        boolean focus(PaneRef pane);

        Optional<PaneRef> currentPane();
    }

    private final CodingAgentRegistry registry;
    private final PaneLocator locator;
    private final PaneFocuser focuser;

    public CodingAgentNavigator(CodingAgentRegistry registry, PaneLocator locator, PaneFocuser focuser) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.focuser = Objects.requireNonNull(focuser, "focuser");
    }

    /** Focuses {@code pane}; false when the pane is gone or the focuser declined. */
    public boolean focus(PaneRef pane) {
        if (pane == null) {
            return false;
        }
        return focuser.focus(pane);
    }

    /**
     * Focuses the next BLOCKED agent after the current pane (wrapping around); empty when none is
     * blocked or the focuser declined.
     */
    public Optional<PaneRef> focusNextBlocked() {
        Optional<PaneRef> next = nextBlocked(orderedEntries(), currentPane().orElse(null));
        if (next.isEmpty()) {
            return Optional.empty();
        }
        return focuser.focus(next.get()) ? next : Optional.empty();
    }

    /** The pane the user is currently on, if it is a terminal pane. */
    public Optional<PaneRef> currentPane() {
        return focuser.currentPane();
    }

    /**
     * Every entry in panel order: BLOCKED first, longest in state first, then window / tab / pane
     * ({@link #displayOrder}).
     */
    public List<CodingAgentEntry> orderedEntries() {
        List<CodingAgentEntry> entries = new ArrayList<>(registry.entries());
        entries.sort(Comparator
            .comparingInt((CodingAgentEntry entry) -> entry.state() == CodingAgentState.BLOCKED ? 0 : 1)
            .thenComparingLong(CodingAgentEntry::stateSinceMillis)
            .thenComparing(displayOrder(locator)));
        return Collections.unmodifiableList(entries);
    }

    /**
     * The first BLOCKED entry after {@code current} in {@code ordered}, wrapping around; the first
     * BLOCKED entry when {@code current} is null or not listed; empty when nothing is blocked.
     */
    public static Optional<PaneRef> nextBlocked(List<CodingAgentEntry> ordered, PaneRef current) {
        if (ordered == null || ordered.isEmpty()) {
            return Optional.empty();
        }
        int size = ordered.size();
        int start = -1;
        if (current != null) {
            for (int i = 0; i < size; i++) {
                if (current.equals(ordered.get(i).pane())) {
                    start = i;
                    break;
                }
            }
        }
        for (int step = 1; step <= size; step++) {
            CodingAgentEntry candidate = ordered.get((start + step) % size);
            if (candidate.state() == CodingAgentState.BLOCKED) {
                return Optional.of(candidate.pane());
            }
        }
        return Optional.empty();
    }

    /**
     * Sorts by window index, tab title (case-insensitive), pane index; entries the locator cannot
     * place sort last; ties fall back to tab id and pane id.
     */
    public static Comparator<CodingAgentEntry> displayOrder(PaneLocator locator) {
        Objects.requireNonNull(locator, "locator");
        Comparator<PaneLocation> byLocation = Comparator
            .comparingInt(PaneLocation::windowIndex)
            .thenComparing(location -> location.tabTitle() == null ? "" : location.tabTitle(),
                String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(PaneLocation::paneIndex);
        Comparator<CodingAgentEntry> byPane = Comparator
            .comparing((CodingAgentEntry entry) -> entry.pane().tabId(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(entry -> entry.pane().paneId(), Comparator.nullsLast(Comparator.naturalOrder()));
        return (a, b) -> {
            PaneLocation la = locator.locate(a.pane()).orElse(null);
            PaneLocation lb = locator.locate(b.pane()).orElse(null);
            if (la != null && lb != null) {
                int result = byLocation.compare(la, lb);
                if (result != 0) {
                    return result;
                }
            } else if (la != null) {
                return -1;
            } else if (lb != null) {
                return 1;
            }
            return byPane.compare(a, b);
        };
    }
}
