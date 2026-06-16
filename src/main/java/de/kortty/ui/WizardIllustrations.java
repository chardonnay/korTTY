package de.kortty.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * Small, dark-theme friendly line-art illustrations for the AI profile setup wizard.
 *
 * <p>JavaFX {@code ImageView} cannot load {@code .svg} files, so illustrations are rendered
 * from inline SVG path data via {@link SVGPath} (the established korTTY pattern, see
 * {@code SnippetDiagramDialog.icon}). Each illustration is composed of one or more stroked
 * paths in a 24&times;24 coordinate space and scaled up to a fixed display size.
 */
final class WizardIllustrations {

    /** Display size (px) of the square illustration area in the wizard header. */
    private static final double DISPLAY_SIZE = 92;
    /** Authoring coordinate space of the SVG paths. */
    private static final double VIEWPORT = 24;

    // A light stroke so the line-art stays clearly visible on the dark dialog background.
    private static final String STROKE_STYLE =
        "-fx-stroke: #cfd6df;"
        + "-fx-fill: transparent;"
        + "-fx-stroke-width: 1.3;"
        + "-fx-stroke-line-cap: round;"
        + "-fx-stroke-line-join: round;";

    // Each value is one or more SVG path strings that together form the illustration.
    private static final Map<String, String[]> PATHS = Map.ofEntries(
        // Three-way fork (cloud / server / terminal) suggesting a choice of connection type.
        Map.entry("welcome", new String[] {
            "M12 2 C12.7 8.6 15.4 11.3 22 12 C15.4 12.7 12.7 15.4 12 22 C11.3 15.4 8.6 12.7 2 12 C8.6 11.3 11.3 8.6 12 2 Z",
            "M6 19 h0.01 M18 19 h0.01"
        }),
        // Cloud with an upward arrow (remote service).
        Map.entry("cloud", new String[] {
            "M7 17 A3.5 3.5 0 0 1 7 10 A4.8 4.8 0 0 1 16.4 10.3 A3.2 3.2 0 0 1 17 17 Z",
            "M12 16 L12 10 M9.7 12 L12 9.6 L14.3 12"
        }),
        // A key (API key entry).
        Map.entry("key", new String[] {
            "M8.5 15 m-3.5 0 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0",
            "M11 12.5 L20 3.5 M16.5 6 L19 8.5 M13.5 9 L15.5 11"
        }),
        // Desktop tower / local server box.
        Map.entry("localserver", new String[] {
            "M5 3.5 H19 V10.5 H5 Z",
            "M5 13 H19 V20.5 H5 Z",
            "M7.4 7 h3.4 M7.4 16.5 h3.4",
            "M15.5 6.4 m-0.7 0 a0.7 0.7 0 1 0 1.4 0 a0.7 0.7 0 1 0 -1.4 0",
            "M15.5 15.9 m-0.7 0 a0.7 0.7 0 1 0 1.4 0 a0.7 0.7 0 1 0 -1.4 0"
        }),
        // Chip / model with pins and a selection caret.
        Map.entry("model", new String[] {
            "M7 7 H17 V17 H7 Z",
            "M10.5 10.5 H13.5 V13.5 H10.5 Z",
            "M9 7 V4 M12 7 V4 M15 7 V4 M9 17 V20 M12 17 V20 M15 17 V20",
            "M7 9 H4 M7 12 H4 M7 15 H4 M17 9 H20 M17 12 H20 M17 15 H20"
        }),
        // Terminal window with a prompt (command-line tool).
        Map.entry("cli", new String[] {
            "M4 5 H20 V19 H4 Z",
            "M7 10 L9.6 12.5 L7 15 M12 15 H16"
        }),
        // Terminal window with a checkmark (installation found).
        Map.entry("cli-check", new String[] {
            "M4 5 H20 V19 H4 Z",
            "M7 9 L9.2 11.2 L7 13.4",
            "M11.5 13.5 L13.5 15.5 L17.5 11"
        }),
        // A tag / label (naming the profile).
        Map.entry("name", new String[] {
            "M3.5 4 H12 L20.5 12.5 L12.5 20.5 L4 12 Z",
            "M7.5 8 m-1.1 0 a1.1 1.1 0 1 0 2.2 0 a1.1 1.1 0 1 0 -2.2 0"
        }),
        // A plug being inserted (connection test).
        Map.entry("test", new String[] {
            "M9 3.5 V8 M15 3.5 V8",
            "M6.5 8 H17.5 V12 A5.5 5.5 0 0 1 6.5 12 Z",
            "M12 17.5 V21"
        }),
        // Big checkmark in a circle (success state).
        Map.entry("success", new String[] {
            "M12 12 m-9 0 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
            "M7.5 12.2 L10.8 15.5 L16.8 8.5"
        })
    );

    private WizardIllustrations() {
    }

    /**
     * Builds the illustration {@link Node} for the given key, or an empty placeholder of the
     * same size when the key is unknown.
     */
    static Node forKey(String key) {
        return forKey(key, DISPLAY_SIZE);
    }

    static Node forKey(String key, double size) {
        String[] paths = key != null ? PATHS.get(key) : null;
        Group group = new Group();
        if (paths != null) {
            for (String pathData : paths) {
                SVGPath path = new SVGPath();
                path.setContent(pathData);
                path.setStyle(STROKE_STYLE);
                group.getChildren().add(path);
            }
        }
        double scale = size / VIEWPORT;
        group.setScaleX(scale);
        group.setScaleY(scale);
        StackPane holder = new StackPane(group);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        return holder;
    }
}
