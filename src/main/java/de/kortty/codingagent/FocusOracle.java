package de.kortty.codingagent;

/**
 * Answers "is the user looking at this pane right now". Implemented by the UI bridge on the FX
 * thread (the pane's widget is the focused widget of the selected tab of a foreground window) and
 * faked in tests.
 */
public interface FocusOracle {

    /** True when the pane is visible, selected and focused in a foreground window. */
    boolean isSeen(PaneRef pane);

    /** True when any application window is the foreground window. */
    boolean isAnyWindowFocused();

    /** An oracle for which nothing is ever seen. */
    FocusOracle NEVER = new FocusOracle() {
        @Override
        public boolean isSeen(PaneRef pane) {
            return false;
        }

        @Override
        public boolean isAnyWindowFocused() {
            return false;
        }
    };
}
