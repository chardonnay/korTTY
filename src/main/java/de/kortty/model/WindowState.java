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
    
    @XmlElement
    private int activeTabIndex = 0;
    
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
    
    @Override
    public String toString() {
        return "WindowState{" +
                "windowId='" + windowId + '\'' +
                ", tabs=" + tabs.size() +
                '}';
    }
}
