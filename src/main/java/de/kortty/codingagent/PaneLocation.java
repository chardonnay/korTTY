package de.kortty.codingagent;

import java.util.function.BiFunction;

/**
 * Where a pane lives right now, resolved on demand by {@link PaneLocator}. {@code windowIndex} and
 * {@code paneIndex} are 0-based positions (in the open-window list and in the tab's ordered widgets)
 * and are displayed 1-based; {@code workingDirectory} may be null when unknown.
 */
public record PaneLocation(int windowIndex, int windowCount, String tabTitle, int paneIndex, int paneCount,
                           String workingDirectory) {

    private static final String SEPARATOR = " › ";
    private static final String KEY_LOCATION = "codingAgent.panel.location";
    private static final String KEY_LOCATION_NO_PANE = "codingAgent.panel.locationNoPane";
    private static final String KEY_WINDOW = "codingAgent.panel.window";

    /** "tab › Pane n"; the pane part only when the tab is split. Language-neutral. */
    public String shortText() {
        String title = tabTitle == null ? "" : tabTitle;
        if (paneCount > 1) {
            return title + SEPARATOR + "Pane " + (paneIndex + 1);
        }
        return title;
    }

    /**
     * Localised location: a "Window k › " prefix when more than one window is open (keys
     * codingAgent.panel.location / locationNoPane / window), the pane part when the tab is split and
     * " · ~/dir" when the working directory is known.
     *
     * @param messages (key, args) -&gt; formatted text, typically {@code I18n::get}
     */
    public String text(BiFunction<String, Object[], String> messages) {
        String base;
        if (windowCount > 1) {
            String window = messages.apply(KEY_WINDOW, new Object[] {windowIndex + 1});
            String title = tabTitle == null ? "" : tabTitle;
            if (paneCount > 1) {
                base = messages.apply(KEY_LOCATION, new Object[] {window, title, paneIndex + 1});
            } else {
                base = messages.apply(KEY_LOCATION_NO_PANE, new Object[] {window, title});
            }
        } else {
            base = shortText();
        }
        String cwd = abbreviateHome(workingDirectory, System.getProperty("user.home"));
        if (cwd == null || cwd.isBlank()) {
            return base;
        }
        return base + " · " + cwd;
    }

    /** Replaces a leading {@code home} by "~"; null-safe, returns {@code path} unchanged otherwise. */
    static String abbreviateHome(String path, String home) {
        if (path == null || home == null || home.isBlank()) {
            return path;
        }
        String normalisedHome = home;
        while (normalisedHome.length() > 1 && (normalisedHome.endsWith("/") || normalisedHome.endsWith("\\"))) {
            normalisedHome = normalisedHome.substring(0, normalisedHome.length() - 1);
        }
        if (path.equals(normalisedHome)) {
            return "~";
        }
        if (path.startsWith(normalisedHome + "/") || path.startsWith(normalisedHome + "\\")) {
            return "~" + path.substring(normalisedHome.length());
        }
        return path;
    }
}
