package de.kortty.model;

import jakarta.xml.bind.annotation.*;

/**
 * Represents window position and size.
 */
@XmlRootElement(name = "geometry")
@XmlAccessorType(XmlAccessType.FIELD)
public class WindowGeometry {
    
    @XmlElement
    private double x;
    
    @XmlElement
    private double y;
    
    @XmlElement
    private double width = 800;
    
    @XmlElement
    private double height = 600;
    
    @XmlElement
    private boolean maximized = false;
    
    @XmlElement
    private boolean fullScreen = false;
    
    public WindowGeometry() {
    }
    
    public WindowGeometry(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
    
    public WindowGeometry(WindowGeometry other) {
        this.x = other.x;
        this.y = other.y;
        this.width = other.width;
        this.height = other.height;
        this.maximized = other.maximized;
        this.fullScreen = other.fullScreen;
    }
    
    // Getters and Setters
    
    public double getX() {
        return x;
    }
    
    public void setX(double x) {
        this.x = x;
    }
    
    public double getY() {
        return y;
    }
    
    public void setY(double y) {
        this.y = y;
    }
    
    public double getWidth() {
        return width;
    }
    
    public void setWidth(double width) {
        this.width = width;
    }
    
    public double getHeight() {
        return height;
    }
    
    public void setHeight(double height) {
        this.height = height;
    }
    
    public boolean isMaximized() {
        return maximized;
    }
    
    public void setMaximized(boolean maximized) {
        this.maximized = maximized;
    }
    
    public boolean isFullScreen() {
        return fullScreen;
    }
    
    public void setFullScreen(boolean fullScreen) {
        this.fullScreen = fullScreen;
    }
    
    @Override
    public String toString() {
        return "WindowGeometry{" +
                "x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                ", maximized=" + maximized +
                '}';
    }
}
