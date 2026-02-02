package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import javafx.geometry.Orientation;

/**
 * Represents the split structure of a terminal tab.
 * Can be a single terminal or a recursive split (left/right or top/bottom).
 */
@XmlRootElement(name = "splitPane")
@XmlAccessorType(XmlAccessType.FIELD)
public class SplitPaneState {
    
    /** If this is a leaf node (single terminal), index refers to which connection. */
    @XmlElement
    private Integer widgetIndex;
    
    /** If this is a split node, orientation of the split. */
    @XmlElement
    private String orientation; // "HORIZONTAL" or "VERTICAL"
    
    /** Divider position (0.0-1.0) for split nodes. */
    @XmlElement
    private Double dividerPosition;
    
    /** Left/top child (for split nodes). */
    @XmlElement
    private SplitPaneState leftChild;
    
    /** Right/bottom child (for split nodes). */
    @XmlElement
    private SplitPaneState rightChild;
    
    public SplitPaneState() {
    }
    
    /**
     * Creates a leaf node (single terminal widget).
     */
    public static SplitPaneState createLeaf(int widgetIndex) {
        SplitPaneState state = new SplitPaneState();
        state.widgetIndex = widgetIndex;
        return state;
    }
    
    /**
     * Creates a split node (containing two children).
     */
    public static SplitPaneState createSplit(Orientation orientation, double dividerPosition,
                                              SplitPaneState left, SplitPaneState right) {
        SplitPaneState state = new SplitPaneState();
        state.orientation = orientation.name();
        state.dividerPosition = dividerPosition;
        state.leftChild = left;
        state.rightChild = right;
        return state;
    }
    
    public boolean isLeaf() {
        return widgetIndex != null;
    }
    
    public boolean isSplit() {
        return orientation != null && leftChild != null && rightChild != null;
    }
    
    // Getters and Setters
    
    public Integer getWidgetIndex() {
        return widgetIndex;
    }
    
    public void setWidgetIndex(Integer widgetIndex) {
        this.widgetIndex = widgetIndex;
    }
    
    public String getOrientation() {
        return orientation;
    }
    
    public void setOrientation(String orientation) {
        this.orientation = orientation;
    }
    
    public Orientation getOrientationEnum() {
        return orientation != null ? Orientation.valueOf(orientation) : null;
    }
    
    public Double getDividerPosition() {
        return dividerPosition;
    }
    
    public void setDividerPosition(Double dividerPosition) {
        this.dividerPosition = dividerPosition;
    }
    
    public SplitPaneState getLeftChild() {
        return leftChild;
    }
    
    public void setLeftChild(SplitPaneState leftChild) {
        this.leftChild = leftChild;
    }
    
    public SplitPaneState getRightChild() {
        return rightChild;
    }
    
    public void setRightChild(SplitPaneState rightChild) {
        this.rightChild = rightChild;
    }
    
    @Override
    public String toString() {
        if (isLeaf()) {
            return "Leaf[widget=" + widgetIndex + "]";
        }
        return "Split[" + orientation + ", divider=" + dividerPosition + ", left=" + leftChild + ", right=" + rightChild + "]";
    }
}
