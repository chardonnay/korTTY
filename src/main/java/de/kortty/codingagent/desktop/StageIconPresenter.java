package de.kortty.codingagent.desktop;

import javafx.scene.image.Image;

/**
 * Port through which the Windows badge backend swaps the stage icons of every open main window.
 * Implemented by the UI bridge ({@code stage.getIcons().setAll(icon)} for every
 * {@code MainWindow.getOpenWindows()}); called on the JavaFX thread only.
 */
public interface StageIconPresenter {

    /** Replaces the icons of every open main window with the badged {@code icon}. */
    void applyBadgedIcon(Image icon);

    /** Restores the plain application icon on every open main window. */
    void restorePlainIcon();
}
