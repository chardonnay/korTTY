package de.kortty.ui;

import de.kortty.model.WindowGeometry;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A modal dialog opened with {@code showAndWait()} — like the Connection Manager — must come back
 * at its stored position and size. JavaFX fires DIALOG_SHOWN before the window is shown and then
 * centres it, which used to undo the restored geometry. Run via {@code dialogGeometryShowAndWaitSmoke}.
 */
public final class DialogGeometryShowAndWaitSmoke {

    private DialogGeometryShowAndWaitSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                Dialog<ButtonType> dialog = new Dialog<>();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.setResizable(true);
                dialog.getDialogPane().setContent(new Label("geometry"));
                dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                WindowGeometry stored = new WindowGeometry(120, 140, 820, 610);
                DialogGeometrySupport.restore(dialog, stored);
                dialog.setOnShown(event -> Platform.runLater(() -> {
                    Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
                    result.set(stage.getX() + "," + stage.getY() + "," + stage.getWidth() + "," + stage.getHeight());
                    dialog.setResult(ButtonType.CLOSE);
                    dialog.close();
                }));
                dialog.showAndWait();
            } catch (Exception e) {
                result.set("error: " + e);
            } finally {
                done.countDown();
            }
        });
        done.await(30, TimeUnit.SECONDS);
        Platform.exit();
        String actual = result.get();
        System.out.println("geometry after showAndWait: " + actual);
        if (!"120.0,140.0,820.0,610.0".equals(actual)) {
            System.err.println("dialogGeometryShowAndWaitSmoke FAILED: expected 120,140,820,610");
            System.exit(1);
        }
        System.out.println("dialogGeometryShowAndWaitSmoke OK");
    }
}
