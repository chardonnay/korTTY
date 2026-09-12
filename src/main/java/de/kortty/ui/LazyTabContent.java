package de.kortty.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;

import java.util.function.Supplier;

/**
 * Builds a tab's content on its first selection instead of up front.
 *
 * <p>{@code TabPaneSkin} keeps a content region for every tab in the scene graph and only toggles
 * its visibility, so an eagerly built tab is skinned, styled and laid out on the dialog's first
 * pulse even though it is invisible — and its constructor has already done whatever I/O it does.
 * Deferring the content keeps all of that out of the dialog open until the user actually goes
 * there. The builder runs on the FX thread, exactly once, either when the tab becomes selected or
 * immediately if it already is.</p>
 */
public final class LazyTabContent {

    private static final Object BUILDER_KEY = new Object();

    private LazyTabContent() {
    }

    /** Defers {@code builder} until {@code tab} is first selected; returns nothing so callers keep their field wiring. */
    public static void defer(Tab tab, Supplier<? extends Node> builder) {
        if (tab == null || builder == null) {
            return;
        }
        tab.getProperties().put(BUILDER_KEY, builder);
        if (tab.isSelected()) {
            ensureContent(tab);
            return;
        }
        ChangeListener<Boolean>[] listener = new ChangeListener[1];
        listener[0] = (obs, wasSelected, isSelected) -> {
            if (Boolean.TRUE.equals(isSelected)) {
                tab.selectedProperty().removeListener(listener[0]);
                ensureContent(tab);
            }
        };
        tab.selectedProperty().addListener(listener[0]);
    }

    /** Builds the deferred content now if it has not been built yet; returns the tab's content. */
    public static Node ensureContent(Tab tab) {
        if (tab == null) {
            return null;
        }
        Object pending = tab.getProperties().remove(BUILDER_KEY);
        if (pending instanceof Supplier<?> builder) {
            Object content = builder.get();
            if (content instanceof Node node) {
                tab.setContent(node);
            }
        }
        return tab.getContent();
    }

    /** Whether the tab's content still waits for its first selection. */
    public static boolean isPending(Tab tab) {
        return tab != null && tab.getProperties().get(BUILDER_KEY) instanceof Supplier<?>;
    }
}
