package de.kortty.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages the visibility and position of the LocalFileBrowser panel.
 * Provides a central point for toggling the file browser on the left or right side.
 */
public class LocalFileBrowserManager {

    public enum Position {
        LEFT,
        RIGHT,
        HIDDEN
    }

    private static LocalFileBrowserManager instance;

    private Position currentPosition = Position.HIDDEN;
    private double preferredWidth = 300.0;

    private final List<Consumer<Position>> positionListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> visibilityListeners = new CopyOnWriteArrayList<>();

    private LocalFileBrowserManager() {
    }

    public static synchronized LocalFileBrowserManager getInstance() {
        if (instance == null) {
            instance = new LocalFileBrowserManager();
        }
        return instance;
    }

    /**
     * Shows the file browser at the specified position.
     */
    public void show(Position position) {
        if (position == Position.HIDDEN) {
            hide();
            return;
        }
        Position oldPosition = this.currentPosition;
        this.currentPosition = position;
        notifyPositionListeners(position);
        if (oldPosition != position) {
            notifyVisibilityListeners(true);
        }
    }

    /**
     * Hides the file browser.
     */
    public void hide() {
        if (currentPosition == Position.HIDDEN) {
            return;
        }
        this.currentPosition = Position.HIDDEN;
        notifyPositionListeners(Position.HIDDEN);
        notifyVisibilityListeners(false);
    }

    /**
     * Toggles the file browser at the specified position.
     * If already at this position, hides it. Otherwise shows it there.
     */
    public void toggle(Position position) {
        if (currentPosition == position) {
            hide();
        } else {
            show(position);
        }
    }

    public Position getPosition() {
        return currentPosition;
    }

    public boolean isVisible() {
        return currentPosition != Position.HIDDEN;
    }

    public double getPreferredWidth() {
        return preferredWidth;
    }

    public void setPreferredWidth(double width) {
        this.preferredWidth = Math.max(160.0, Math.min(width, 420.0));
    }

    public void addPositionListener(Consumer<Position> listener) {
        positionListeners.add(listener);
    }

    public void removePositionListener(Consumer<Position> listener) {
        positionListeners.remove(listener);
    }

    public void addVisibilityListener(Consumer<Boolean> listener) {
        visibilityListeners.add(listener);
    }

    public void removeVisibilityListener(Consumer<Boolean> listener) {
        visibilityListeners.remove(listener);
    }

    private void notifyPositionListeners(Position position) {
        for (Consumer<Position> listener : positionListeners) {
            listener.accept(position);
        }
    }

    private void notifyVisibilityListeners(boolean visible) {
        for (Consumer<Boolean> listener : visibilityListeners) {
            listener.accept(visible);
        }
    }
}
