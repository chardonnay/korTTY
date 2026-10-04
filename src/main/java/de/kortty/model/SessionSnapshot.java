package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * What korTTY saves of the running session on this device, so File › Restore Previous Session can
 * bring it back after a restart or a crash: every window with its tabs, written as a
 * {@link Project} with Auto-Reconnect on, and the Recently Closed list.
 *
 * <p>A snapshot never holds screen text, scrollback, timestamps, passwords or temporary SSH keys:
 * the tabs name their saved connection by id, and the closed tabs are kept as ids only (see
 * {@link ClosedTab}). {@code de.kortty.core.SessionSnapshotStore} removes anything else before it
 * writes or after it reads a snapshot.
 */
@XmlRootElement(name = "sessionSnapshot")
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionSnapshot {

    /** The format this version of korTTY writes. */
    public static final int CURRENT_FORMAT = 1;

    @XmlAttribute(name = "format")
    private int format = CURRENT_FORMAT;

    /** The korTTY version that wrote the snapshot. */
    @XmlElement
    private String appVersion;

    /** When the snapshot was written, in milliseconds since the epoch. */
    @XmlElement
    private long savedAtMillis;

    /**
     * Whether korTTY wrote this snapshot while it quit normally; {@code false} in a snapshot written
     * while it was running, which is what a crash leaves behind.
     */
    @XmlElement
    private boolean cleanExit;

    /**
     * Whether the run that wrote this snapshot reopened its previous session (at startup or from
     * File › Restore Previous Session). Together with {@link #stable} and {@link #cleanExit} it tells
     * the next start that korTTY ended unexpectedly right after a restore, which then asks instead of
     * restoring by itself (see {@code de.kortty.core.SessionRestoreDecision}).
     */
    @XmlElement
    private boolean sessionRestored;

    /** Whether korTTY was still running a minute after that restore. */
    @XmlElement
    private boolean stable;

    /** The windows and their tabs; {@code null} or without windows when nothing was open. */
    @XmlElement
    private Project project;

    /** The Recently Closed list, newest first. */
    @XmlElementWrapper(name = "recentlyClosed")
    @XmlElement(name = "entry")
    private List<ClosedEntry> recentlyClosed = new ArrayList<>();

    public SessionSnapshot() {
    }

    /**
     * A snapshot that shares this one's windows, Recently Closed list and restore flags and differs
     * only in {@code cleanExit}. The store writes a copy of what it is given, so sharing is safe.
     */
    public SessionSnapshot withCleanExit(boolean clean) {
        SessionSnapshot copy = new SessionSnapshot();
        copy.format = format;
        copy.appVersion = appVersion;
        copy.savedAtMillis = savedAtMillis;
        copy.cleanExit = clean;
        copy.sessionRestored = sessionRestored;
        copy.stable = stable;
        copy.project = project;
        copy.recentlyClosed = recentlyClosed;
        return copy;
    }

    public int getFormat() {
        return format;
    }

    public void setFormat(int format) {
        this.format = format;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public long getSavedAtMillis() {
        return savedAtMillis;
    }

    public void setSavedAtMillis(long savedAtMillis) {
        this.savedAtMillis = savedAtMillis;
    }

    public boolean isCleanExit() {
        return cleanExit;
    }

    public void setCleanExit(boolean cleanExit) {
        this.cleanExit = cleanExit;
    }

    public boolean isSessionRestored() {
        return sessionRestored;
    }

    public void setSessionRestored(boolean sessionRestored) {
        this.sessionRestored = sessionRestored;
    }

    public boolean isStable() {
        return stable;
    }

    public void setStable(boolean stable) {
        this.stable = stable;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public List<ClosedEntry> getRecentlyClosed() {
        return recentlyClosed;
    }

    public void setRecentlyClosed(List<ClosedEntry> recentlyClosed) {
        this.recentlyClosed = recentlyClosed != null ? recentlyClosed : new ArrayList<>();
    }

    /**
     * One Recently Closed entry: the tabs one close took away.
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class ClosedEntry {

        /** Whether the tabs are those of a closed window, which reopen in a window of their own. */
        @XmlAttribute
        private boolean window;

        @XmlElement(name = "tab")
        private List<ClosedTab> tabs = new ArrayList<>();

        public ClosedEntry() {
        }

        public ClosedEntry(boolean window, List<ClosedTab> tabs) {
            this.window = window;
            setTabs(tabs);
        }

        public boolean isWindow() {
            return window;
        }

        public void setWindow(boolean window) {
            this.window = window;
        }

        public List<ClosedTab> getTabs() {
            return tabs;
        }

        public void setTabs(List<ClosedTab> tabs) {
            this.tabs = tabs != null ? new ArrayList<>(tabs) : new ArrayList<>();
        }
    }

    /**
     * One closed terminal tab, kept as ids only: the saved connection it used, never a copy of that
     * connection, so no password, temporary SSH key or host of an unsaved session reaches the disk.
     * A tab whose connection is not saved (a Quick Connect session) is therefore not kept across a
     * restart.
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class ClosedTab {

        @XmlElement
        private String connectionId;

        /** Whether the tab signed in with a temporary SSH key, which a reopen asks for again. */
        @XmlElement
        private boolean usedTemporaryKey;

        /** The tab group (not the connection's), or {@code null}. */
        @XmlElement
        private String tabGroup;

        /** The name the user gave the tab, or {@code null}. */
        @XmlElement
        private String customTitle;

        @XmlElement
        private String terminalEffectPluginId;

        @XmlElement
        private Double terminalEffectSpeed;

        public ClosedTab() {
        }

        public ClosedTab(String connectionId, boolean usedTemporaryKey, String tabGroup, String customTitle,
                         String terminalEffectPluginId, Double terminalEffectSpeed) {
            this.connectionId = connectionId;
            this.usedTemporaryKey = usedTemporaryKey;
            this.tabGroup = tabGroup;
            this.customTitle = customTitle;
            this.terminalEffectPluginId = terminalEffectPluginId;
            this.terminalEffectSpeed = terminalEffectSpeed;
        }

        public String getConnectionId() {
            return connectionId;
        }

        public void setConnectionId(String connectionId) {
            this.connectionId = connectionId;
        }

        public boolean isUsedTemporaryKey() {
            return usedTemporaryKey;
        }

        public void setUsedTemporaryKey(boolean usedTemporaryKey) {
            this.usedTemporaryKey = usedTemporaryKey;
        }

        public String getTabGroup() {
            return tabGroup;
        }

        public void setTabGroup(String tabGroup) {
            this.tabGroup = tabGroup;
        }

        public String getCustomTitle() {
            return customTitle;
        }

        public void setCustomTitle(String customTitle) {
            this.customTitle = customTitle;
        }

        public String getTerminalEffectPluginId() {
            return terminalEffectPluginId;
        }

        public void setTerminalEffectPluginId(String terminalEffectPluginId) {
            this.terminalEffectPluginId = terminalEffectPluginId;
        }

        public Double getTerminalEffectSpeed() {
            return terminalEffectSpeed;
        }

        public void setTerminalEffectSpeed(Double terminalEffectSpeed) {
            this.terminalEffectSpeed = terminalEffectSpeed;
        }
    }
}
