package de.kortty.ui;

import javafx.scene.control.Button;
import javafx.scene.shape.SVGPath;

/**
 * Inline SVG glyphs (24x24) shared by dialog action-bar buttons, filled with the theme text color.
 * JavaFX {@code ImageView} cannot load {@code .svg} resources, so the path data is inlined.
 */
final class ButtonIcons {

    static final String WIZARD = "M12 2 L14 9 L21 11 L14 13 L12 20 L10 13 L3 11 L10 9 Z";
    static final String ADD = "M11 5 H13 V11 H19 V13 H13 V19 H11 V13 H5 V11 H11 Z";
    static final String TEST = "M13 2 L4 14 H10 L9 22 L20 10 H13 Z";
    static final String DELETE = "M7 8 H17 L16 21 H8 Z M9 4 H15 L16 6 H19 V8 H5 V6 H8 Z";
    static final String REFRESH =
        "M17.65 6.35 C16.2 4.9 14.21 4 12 4 C7.58 4 4 7.58 4 12 C4 16.42 7.58 20 12 20 "
        + "C15.73 20 18.84 17.45 19.73 14 H17.65 C16.83 16.33 14.61 18 12 18 C8.69 18 6 15.31 6 12 "
        + "C6 8.69 8.69 6 12 6 C13.66 6 15.14 6.69 16.22 7.78 L13 11 H20 V4 Z";
    static final String SAVE = "M4 4 H17 L20 7 V20 H4 Z M7 4 H15 V10 H7 Z M8 13 H16 V20 H8 Z";
    static final String SKILLS =
        "M9 21 H15 V22 H9 Z M8 18 H16 V20 H8 Z M12 2 C8.13 2 5 5.13 5 9 C5 11.38 6.19 13.47 8 14.74 "
        + "V17 H16 V14.74 C17.81 13.47 19 11.38 19 9 C19 5.13 15.87 2 12 2 Z";
    static final String OPEN = "M4 4 H20 V16 H8 L4 20 Z";
    static final String RENAME =
        "M3 17.25 V21 H6.75 L17.81 9.94 L14.06 6.19 Z "
        + "M20.71 7.04 C21.1 6.65 21.1 6.02 20.71 5.63 L18.37 3.29 C17.98 2.9 17.35 2.9 16.96 3.29 "
        + "L15.13 5.12 L18.88 8.87 Z";
    static final String DOWNLOAD =
        "M11 3 H13 V12.17 L16.59 8.59 L18 10 L12 16 L6 10 L7.41 8.59 L11 12.17 Z M5 18 H19 V20 H5 Z";
    static final String FOLDER =
        "M2 6 C2 4.9 2.9 4 4 4 H9 L11 6 H20 C21.1 6 22 6.9 22 8 V18 C22 19.1 21.1 20 20 20 H4 "
        + "C2.9 20 2 19.1 2 18 Z";
    static final String GEAR =
        "M19.14 12.94 C19.18 12.64 19.2 12.33 19.2 12 C19.2 11.68 19.18 11.36 19.13 11.06 "
        + "L21.16 9.48 C21.34 9.34 21.39 9.07 21.28 8.87 L19.36 5.55 C19.24 5.33 18.99 5.26 18.77 5.33 "
        + "L16.38 6.29 C15.88 5.91 15.35 5.59 14.76 5.35 L14.4 2.81 C14.36 2.57 14.16 2.4 13.92 2.4 "
        + "H10.08 C9.84 2.4 9.65 2.57 9.61 2.81 L9.25 5.35 C8.66 5.59 8.12 5.92 7.63 6.29 L5.24 5.33 "
        + "C5.02 5.25 4.77 5.33 4.65 5.55 L2.74 8.87 C2.62 9.08 2.66 9.34 2.86 9.48 L4.89 11.06 "
        + "C4.84 11.36 4.8 11.69 4.8 12 C4.8 12.31 4.82 12.64 4.87 12.94 L2.84 14.52 "
        + "C2.66 14.66 2.61 14.93 2.72 15.13 L4.64 18.45 C4.76 18.67 5.01 18.74 5.23 18.67 "
        + "L7.62 17.71 C8.12 18.09 8.65 18.41 9.24 18.65 L9.6 21.19 C9.65 21.43 9.84 21.6 10.08 21.6 "
        + "H13.92 C14.16 21.6 14.36 21.43 14.39 21.19 L14.75 18.65 C15.34 18.41 15.88 18.09 16.37 17.71 "
        + "L18.76 18.67 C18.98 18.75 19.23 18.67 19.35 18.45 L21.27 15.13 C21.39 14.91 21.34 14.66 "
        + "21.15 14.52 L19.14 12.94 Z M12 15.6 C10.02 15.6 8.4 13.98 8.4 12 C8.4 10.02 10.02 8.4 12 8.4 "
        + "C13.98 8.4 15.6 10.02 15.6 12 C15.6 13.98 13.98 15.6 12 15.6 Z";
    static final String PLAY = "M8 5 L19 12 L8 19 Z";
    static final String STOP = "M6 6 H18 V18 H6 Z";
    static final String SEARCH =
        "M15.5 14 H14.71 L14.43 13.73 C15.41 12.59 16 11.11 16 9.5 C16 5.91 13.09 3 9.5 3 "
        + "C5.91 3 3 5.91 3 9.5 C3 13.09 5.91 16 9.5 16 C11.11 16 12.59 15.41 13.73 14.43 "
        + "L14 14.71 V15.5 L19 20.49 L20.49 19 Z M9.5 14 C7.01 14 5 11.99 5 9.5 C5 7.01 7.01 5 9.5 5 "
        + "C11.99 5 14 7.01 14 9.5 C14 11.99 11.99 14 9.5 14 Z";
    static final String MORE = "M7.41 7.84 L12 12.42 L16.59 7.84 L18 9.25 L12 15.25 L6 9.25 Z";
    static final String PAUSE = "M6 5 H10 V19 H6 Z M14 5 H18 V19 H14 Z";
    static final String CANCEL =
        "M19 6.41 L17.59 5 L12 10.59 L6.41 5 L5 6.41 L10.59 12 L5 17.59 L6.41 19 L12 13.41 "
        + "L17.59 19 L19 17.59 L13.41 12 Z";

    private ButtonIcons() {
    }

    static void apply(Button button, String svgPathData) {
        SVGPath icon = new SVGPath();
        icon.setContent(svgPathData);
        // Match the button text color (terminal.css .button) so icons stay visible on the dark theme.
        icon.setStyle("-fx-fill: #cccccc;");
        icon.setScaleX(0.6);
        icon.setScaleY(0.6);
        button.setGraphic(icon);
        button.setGraphicTextGap(6);
    }
}
