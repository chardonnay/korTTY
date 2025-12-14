package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a project containing multiple SSH sessions with their state.
 */
@XmlRootElement(name = "project")
@XmlAccessorType(XmlAccessType.FIELD)
public class Project {
    
    @XmlElement
    private String name;
    
    @XmlElement
    private String description;
    
    @XmlElement
    private LocalDateTime createdAt;
    
    @XmlElement
    private LocalDateTime lastModified;
    
    @XmlElement
    private boolean autoReconnect = false;
    
    @XmlElementWrapper(name = "windows")
    @XmlElement(name = "window")
    private List<WindowState> windows = new ArrayList<>();
    
    @XmlElement
    private String projectFilePath;
    
    public Project() {
        this.createdAt = LocalDateTime.now();
        this.lastModified = LocalDateTime.now();
    }
    
    public Project(String name) {
        this();
        this.name = name;
    }
    
    // Getters and Setters
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }
    
    public boolean isAutoReconnect() {
        return autoReconnect;
    }
    
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }
    
    public List<WindowState> getWindows() {
        return windows;
    }
    
    public void setWindows(List<WindowState> windows) {
        this.windows = windows;
    }
    
    public void addWindow(WindowState window) {
        this.windows.add(window);
    }
    
    public String getProjectFilePath() {
        return projectFilePath;
    }
    
    public void setProjectFilePath(String projectFilePath) {
        this.projectFilePath = projectFilePath;
    }
    
    public void touch() {
        this.lastModified = LocalDateTime.now();
    }
    
    @Override
    public String toString() {
        return "Project{" +
                "name='" + name + '\'' +
                ", windows=" + windows.size() +
                ", autoReconnect=" + autoReconnect +
                '}';
    }
}
