package de.kortty.ui;

/**
 * Pure, UI-free helpers for stepping through the Settings dialog's section tabs with the
 * previous/next navigation buttons. Kept free of JavaFX so the index clamping, the button
 * enablement and the position label can be unit-tested without a UI toolkit.
 */
public final class SettingsSectionNavigation {

    private SettingsSectionNavigation() {
    }

    /** The selection index after pressing "previous", clamped to the first section. */
    public static int previous(int index) {
        return index <= 0 ? 0 : index - 1;
    }

    /** The selection index after pressing "next", clamped to the last section. */
    public static int next(int index, int count) {
        if (count <= 0) {
            return 0;
        }
        int last = count - 1;
        return index >= last ? last : index + 1;
    }

    /** True when there is an earlier section to move to (i.e. the "previous" arrow is usable). */
    public static boolean canGoPrevious(int index) {
        return index > 0;
    }

    /** True when there is a later section to move to (i.e. the "next" arrow is usable). */
    public static boolean canGoNext(int index, int count) {
        return index >= 0 && index < count - 1;
    }

    /**
     * The label shown between the navigation arrows, e.g. {@code "Appearance  (3/18)"}. Falls back to
     * the bare {@code "position/total"} when the title is blank, and to just the (trimmed) title when
     * there is no usable section index.
     */
    public static String positionLabel(String title, int index, int count) {
        String trimmed = title == null ? "" : title.strip();
        if (count <= 0 || index < 0 || index >= count) {
            return trimmed;
        }
        String position = (index + 1) + "/" + count;
        return trimmed.isEmpty() ? position : trimmed + "  (" + position + ")";
    }
}
