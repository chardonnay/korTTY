package de.kortty.model;

import jakarta.xml.bind.annotation.*;
import javafx.geometry.Orientation;

/**
 * Represents the split structure of a terminal tab.
 * Can be a single terminal or a recursive split (left/right or top/bottom).
 *
 * <p>A leaf names the pane by its position ({@code widgetIndex}, the panes counted from left to
 * right and top to bottom) and may carry optional fields that files written before them simply do
 * not have:
 * <ul>
 *   <li>{@code connectionId}: the saved connection the pane runs when it is not the tab's (a pane
 *       split to another server); absent for a pane on the tab's own connection.</li>
 *   <li>{@code currentDirectory} and {@code scrollbackRef}: session-only. Only a session snapshot
 *       of this device may carry them; a project file is shareable, so
 *       {@code ProjectLeafFieldSanitizer} removes them from every project that is loaded or
 *       saved.</li>
 * </ul>
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

    /** Leaf only: the saved connection the pane runs when it is not the tab's; null for the tab's. */
    @XmlElement
    private String connectionId;

    /** Leaf only, session snapshots only: the local shell's working directory. */
    @XmlElement
    private String currentDirectory;

    /** Leaf only, session snapshots only: the name of the pane's saved scrollback file. */
    @XmlElement
    private String scrollbackRef;
    
    public SplitPaneState() {
    }
    
    /**
     * Creates a leaf node (single terminal widget).
     */
    public static SplitPaneState createLeaf(int widgetIndex) {
        return createLeaf(widgetIndex, null);
    }

    /**
     * Creates a leaf node for a pane that runs {@code connectionId}, or the tab's connection when it
     * is {@code null}.
     */
    public static SplitPaneState createLeaf(int widgetIndex, String connectionId) {
        SplitPaneState state = new SplitPaneState();
        state.widgetIndex = widgetIndex;
        state.connectionId = connectionId;
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
    
    /**
     * A copy of this layout down to its last node, every field included, so a caller can sanitize or
     * change it without touching the layout another object still holds (a restored tab's pending
     * layout, for instance). Walks the tree without recursion, so a deeply nested hand-made file
     * cannot overflow the stack.
     */
    public SplitPaneState deepCopy() {
        SplitPaneState root = copyNodeFields(this);
        java.util.Deque<SplitPaneState[]> pending = new java.util.ArrayDeque<>();
        pending.push(new SplitPaneState[] {this, root});
        while (!pending.isEmpty()) {
            SplitPaneState[] pair = pending.pop();
            SplitPaneState source = pair[0];
            SplitPaneState target = pair[1];
            if (source.leftChild != null) {
                target.leftChild = copyNodeFields(source.leftChild);
                pending.push(new SplitPaneState[] {source.leftChild, target.leftChild});
            }
            if (source.rightChild != null) {
                target.rightChild = copyNodeFields(source.rightChild);
                pending.push(new SplitPaneState[] {source.rightChild, target.rightChild});
            }
        }
        return root;
    }

    private static SplitPaneState copyNodeFields(SplitPaneState source) {
        SplitPaneState copy = new SplitPaneState();
        copy.widgetIndex = source.widgetIndex;
        copy.orientation = source.orientation;
        copy.dividerPosition = source.dividerPosition;
        copy.connectionId = source.connectionId;
        copy.currentDirectory = source.currentDirectory;
        copy.scrollbackRef = source.scrollbackRef;
        return copy;
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
    
    /** The split's orientation, or {@code null} for a leaf and for a value that names none (a hand-made file). */
    public Orientation getOrientationEnum() {
        if (orientation == null) {
            return null;
        }
        try {
            return Orientation.valueOf(orientation);
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getCurrentDirectory() {
        return currentDirectory;
    }

    public void setCurrentDirectory(String currentDirectory) {
        this.currentDirectory = currentDirectory;
    }

    public String getScrollbackRef() {
        return scrollbackRef;
    }

    public void setScrollbackRef(String scrollbackRef) {
        this.scrollbackRef = scrollbackRef;
    }
    
    @Override
    public String toString() {
        if (isLeaf()) {
            // The working directory is not printed: a log line need not show where someone works.
            return "Leaf[widget=" + widgetIndex + (connectionId != null ? ", connection=" + connectionId : "") + "]";
        }
        return "Split[" + orientation + ", divider=" + dividerPosition + ", left=" + leftChild + ", right=" + rightChild + "]";
    }
}
