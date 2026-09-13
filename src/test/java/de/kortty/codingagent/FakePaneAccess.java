package de.kortty.codingagent;

import com.sithtermfx.core.TtyConnector;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Test double for {@link PaneAccess}: connectors, bracketed-paste flags and the shortcut check are scripted. */
public final class FakePaneAccess implements PaneAccess {

    private final Map<PaneRef, TtyConnector> connectors = new HashMap<>();
    private final Set<PaneRef> bracketed = new HashSet<>();
    private Predicate<String> intercept = line -> false;
    private String hostShortcutCommandName = "agent";

    @Override
    public Optional<TtyConnector> connectorFor(PaneRef pane) {
        return Optional.ofNullable(connectors.get(pane));
    }

    @Override
    public boolean isBracketedPasteEnabled(PaneRef pane) {
        return bracketed.contains(pane);
    }

    @Override
    public boolean wouldHostShortcutIntercept(String firstLine) {
        return intercept.test(firstLine);
    }

    @Override
    public String hostShortcutCommandName() {
        return hostShortcutCommandName;
    }

    /** Registers a fresh recording connector for {@code pane} and returns it. */
    public RecordingTtyConnector connect(PaneRef pane) {
        RecordingTtyConnector connector = new RecordingTtyConnector();
        connectors.put(pane, connector);
        return connector;
    }

    public void register(PaneRef pane, TtyConnector connector) {
        connectors.put(pane, connector);
    }

    public void remove(PaneRef pane) {
        connectors.remove(pane);
        bracketed.remove(pane);
    }

    public void setBracketedPaste(PaneRef pane, boolean enabled) {
        if (enabled) {
            bracketed.add(pane);
        } else {
            bracketed.remove(pane);
        }
    }

    /** Scripts {@link #wouldHostShortcutIntercept}. */
    public void setIntercept(Predicate<String> intercept) {
        this.intercept = intercept == null ? line -> false : intercept;
    }

    public void setHostShortcutCommandName(String name) {
        this.hostShortcutCommandName = name;
    }
}
