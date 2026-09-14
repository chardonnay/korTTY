package de.kortty.codingagent;

import java.util.HashSet;
import java.util.Set;

/** Test double: panes are seen only when explicitly marked; any-window-focused defaults to true. */
public final class FakeFocusOracle implements FocusOracle {

    private final Set<PaneRef> seen = new HashSet<>();
    private boolean anyWindowFocused = true;

    @Override
    public boolean isSeen(PaneRef pane) {
        return pane != null && seen.contains(pane);
    }

    @Override
    public boolean isAnyWindowFocused() {
        return anyWindowFocused;
    }

    /** Marks or unmarks {@code pane} as the pane the user is looking at. */
    public void setSeen(PaneRef pane, boolean value) {
        if (value) {
            seen.add(pane);
        } else {
            seen.remove(pane);
        }
    }

    /** Nothing is seen any more. */
    public void clearSeen() {
        seen.clear();
    }

    public void setAnyWindowFocused(boolean value) {
        this.anyWindowFocused = value;
    }
}
