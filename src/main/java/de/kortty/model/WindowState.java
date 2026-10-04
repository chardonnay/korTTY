package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the state of a window including its tabs.
 */
@XmlRootElement(name = "windowState")
@XmlAccessorType(XmlAccessType.FIELD)
public class WindowState {
    
    @XmlElement
    private String windowId;
    
    @XmlElement
    private WindowGeometry geometry;
    
    @XmlElementWrapper(name = "tabs")
    @XmlElement(name = "tab")
    private List<SessionState> tabs = new ArrayList<>();
    
    /**
     * The position of the active tab in {@link #tabs}, or -1 when the active tab was not saved (an
     * AI or tool tab). Only files without {@link #activeSessionId} rely on it: the order of the
     * restored tabs differs from the saved one once tab groups sort them or a remote file arrives
     * late, so an index alone may point at another tab.
     */
    @XmlElement
    private int activeTabIndex = 0;

    /**
     * The {@link SessionState#getSessionId() session id} of the tab that was active, so opening the
     * project selects that tab wherever it ends up. Null in files saved before it existed, and when
     * the active tab was not saved.
     */
    @XmlElement
    private String activeSessionId;
    
    /** Dashboard visibility when project was saved. */
    @XmlElement
    private Boolean dashboardVisible;
    
    /** Dashboard divider position (0.0-1.0) when project was saved. */
    @XmlElement
    private Double dashboardDividerPosition;
    
    public WindowState() {
        this.geometry = new WindowGeometry();
    }
    
    public WindowState(String windowId) {
        this();
        this.windowId = windowId;
    }
    
    // Getters and Setters
    
    public String getWindowId() {
        return windowId;
    }
    
    public void setWindowId(String windowId) {
        this.windowId = windowId;
    }
    
    public WindowGeometry getGeometry() {
        return geometry;
    }
    
    public void setGeometry(WindowGeometry geometry) {
        this.geometry = geometry;
    }
    
    public List<SessionState> getTabs() {
        return tabs;
    }
    
    public void setTabs(List<SessionState> tabs) {
        this.tabs = tabs;
    }
    
    public void addTab(SessionState tab) {
        this.tabs.add(tab);
    }
    
    public int getActiveTabIndex() {
        return activeTabIndex;
    }
    
    public void setActiveTabIndex(int activeTabIndex) {
        this.activeTabIndex = activeTabIndex;
    }
    
    public String getActiveSessionId() {
        return activeSessionId;
    }

    public void setActiveSessionId(String activeSessionId) {
        this.activeSessionId = activeSessionId;
    }
    
    public Boolean getDashboardVisible() {
        return dashboardVisible;
    }
    
    public void setDashboardVisible(Boolean dashboardVisible) {
        this.dashboardVisible = dashboardVisible;
    }
    
    public Double getDashboardDividerPosition() {
        return dashboardDividerPosition;
    }
    
    public void setDashboardDividerPosition(Double dashboardDividerPosition) {
        this.dashboardDividerPosition = dashboardDividerPosition;
    }
    
    @Override
    public String toString() {
        return "WindowState{" +
                "windowId='" + windowId + '\'' +
                ", tabs=" + tabs.size() +
                '}';
    }
}
