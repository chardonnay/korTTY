package de.kortty.ui;

/**
 * Pure, UI-free helpers for stepping through the app-design choices with the previous/next
 * buttons next to the App-Design dropdown. Stepping wraps around (the last design's "next" is
 * the first one and vice versa) so the user can cycle endlessly in either direction. Kept free of
 * JavaFX so the index arithmetic can be unit-tested without a UI toolkit.
 */
public final class AppDesignNavigation {

    private AppDesignNavigation() {
    }

    /** The selection index after pressing "next", wrapping from the last design back to the first. */
    public static int next(int index, int count) {
        if (count <= 0) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        return (index + 1) % count;
    }

    /** The selection index after pressing "previous", wrapping from the first design to the last. */
    public static int previous(int index, int count) {
        if (count <= 0) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        return (index - 1 + count) % count;
    }
}
