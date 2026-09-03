package de.kortty.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;

/** Follows layout growth until the reader scrolls away; layout itself never changes reader intent. */
final class ChatAutoScrollSupport {
    private final ScrollPane scroll;
    private final Region content;
    private boolean following = true;
    private boolean pending;
    private boolean adjusting;
    private boolean userScrolling;
    private boolean draggingScrollbar;

    ChatAutoScrollSupport(ScrollPane scroll, Region content) {
        this.scroll = scroll;
        this.content = content;
        content.heightProperty().addListener((obs, before, after) -> requestScroll());
        scroll.viewportBoundsProperty().addListener((obs, before, after) -> requestScroll());
        scroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() != 0) {
                beginUserScroll(event.getDeltaY() > 0);
            }
        });
        scroll.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case UP, PAGE_UP, HOME -> beginUserScroll(true);
                case DOWN, PAGE_DOWN, END, SPACE -> beginUserScroll(event.isShiftDown());
                default -> { }
            }
        });
        scroll.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (isVerticalScrollbar(event.getTarget())) {
                draggingScrollbar = true;
                beginUserScroll(false);
                following = false;
            }
        });
        scroll.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (draggingScrollbar) {
                draggingScrollbar = false;
                finishUserScroll();
            }
        });
        scroll.vvalueProperty().addListener((obs, before, after) -> {
            if (!adjusting && (userScrolling || draggingScrollbar)) {
                following = atBottom();
            }
        });
    }

    void pause() {
        following = false;
    }

    boolean isFollowing() {
        return following;
    }

    private boolean isVerticalScrollbar(Object target) {
        for (Node node = target instanceof Node n ? n : null; node != null && node != scroll; node = node.getParent()) {
            if (node instanceof ScrollBar bar) {
                return bar.getOrientation() == javafx.geometry.Orientation.VERTICAL;
            }
        }
        return false;
    }

    private void beginUserScroll(boolean upward) {
        userScrolling = true;
        if (upward && content.getHeight() > scroll.getViewportBounds().getHeight()) {
            following = false;
        }
        Platform.runLater(this::finishUserScroll);
    }

    private void finishUserScroll() {
        if (!draggingScrollbar) {
            following = atBottom();
            userScrolling = false;
            requestScroll();
        }
    }

    private boolean atBottom() {
        double overflow = content.getHeight() - scroll.getViewportBounds().getHeight();
        double range = scroll.getVmax() - scroll.getVmin();
        return overflow <= 1 || range <= 0
            || (scroll.getVmax() - scroll.getVvalue()) / range * overflow <= 2;
    }

    void requestScroll() {
        if (!following || pending) {
            return;
        }
        pending = true;
        Platform.runLater(() -> {
            try {
                if (following && !userScrolling && !draggingScrollbar) {
                    adjusting = true;
                    scroll.applyCss();
                    scroll.layout();
                    content.layout();
                    scroll.setVvalue(scroll.getVmax());
                }
            } finally {
                adjusting = false;
                pending = false;
            }
        });
    }
}
