package de.kortty.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a hierarchical path for connection groups.
 * Groups are separated by forward slashes (/).
 */
public class GroupPath {
    public static final String SEPARATOR = "/";
    public static final GroupPath ROOT = new GroupPath("");
    
    private final List<String> segments;
    
    /**
     * Creates a GroupPath from a string path.
     * @param path The path string (e.g., "Work/Servers" or empty for root)
     */
    public GroupPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            this.segments = new ArrayList<>();
        } else {
            this.segments = new ArrayList<>();
            for (String segment : path.split("\\" + SEPARATOR)) {
                String trimmed = segment.trim();
                if (!trimmed.isEmpty()) {
                    this.segments.add(trimmed);
                }
            }
        }
    }
    
    /**
     * Creates a GroupPath from a list of segments.
     * @param segments The path segments
     */
    public GroupPath(List<String> segments) {
        this.segments = new ArrayList<>(segments);
    }
    
    /**
     * Returns the full path as a string.
     * @return The path string (e.g., "Work/Servers")
     */
    public String getPath() {
        if (segments.isEmpty()) {
            return "";
        }
        return String.join(SEPARATOR, segments);
    }
    
    /**
     * Returns the path segments.
     * @return Unmodifiable list of segments
     */
    public List<String> getSegments() {
        return Collections.unmodifiableList(segments);
    }
    
    /**
     * Returns the parent path.
     * @return The parent path, or ROOT if this is a top-level group
     */
    public GroupPath getParent() {
        if (segments.isEmpty()) {
            return null;
        }
        if (segments.size() == 1) {
            return ROOT;
        }
        return new GroupPath(segments.subList(0, segments.size() - 1));
    }
    
    /**
     * Returns the name of this group (last segment).
     * @return The group name, or empty string for root
     */
    public String getName() {
        if (segments.isEmpty()) {
            return "";
        }
        return segments.get(segments.size() - 1);
    }
    
    /**
     * Creates a new path by appending a segment.
     * @param name The segment to append
     * @return A new GroupPath with the appended segment
     */
    public GroupPath append(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }
        List<String> newSegments = new ArrayList<>(segments);
        newSegments.add(name.trim());
        return new GroupPath(newSegments);
    }
    
    /**
     * Checks if this path is a child of the given path.
     * @param other The potential parent path
     * @return true if this is a child of other
     */
    public boolean isChildOf(GroupPath other) {
        if (other == null || segments.size() <= other.segments.size()) {
            return false;
        }
        for (int i = 0; i < other.segments.size(); i++) {
            if (!segments.get(i).equals(other.segments.get(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if this path is a parent of the given path.
     * @param other The potential child path
     * @return true if this is a parent of other
     */
    public boolean isParentOf(GroupPath other) {
        return other != null && other.isChildOf(this);
    }
    
    /**
     * Checks if this is the root path.
     * @return true if this is the root path
     */
    public boolean isRoot() {
        return segments.isEmpty();
    }
    
    /**
     * Returns the depth of this path (number of segments).
     * @return The depth (0 for root)
     */
    public int getDepth() {
        return segments.size();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupPath groupPath = (GroupPath) o;
        return Objects.equals(segments, groupPath.segments);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }
    
    @Override
    public String toString() {
        return getPath();
    }
}
