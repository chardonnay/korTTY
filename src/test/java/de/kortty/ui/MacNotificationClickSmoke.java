package de.kortty.ui;

import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.PlatformProbe;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Manual native smoke: click the demo notification to restore a minimised window and select its tab. */
public final class MacNotificationClickSmoke {
    private MacNotificationClickSmoke() { }

    public static void main(String[] args) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        DesktopNotifier notifier = DesktopNotifier.createDefault(PlatformProbe.fromSystem(), Platform::runLater);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            Stage stage = new Stage();
            Tab target = new Tab("Notification target");
            Tab other = new Tab("Other tab");
            TabPane tabs = new TabPane(other, target);
            stage.setTitle("korTTY notification click test");
            stage.setScene(new Scene(tabs, 480, 160));
            stage.show();
            stage.setIconified(true);
            AtomicBoolean activated = new AtomicBoolean();
            Runnable verify = () -> {
                if (activated.get() && stage.isShowing() && !stage.isIconified() && stage.isFocused()
                        && tabs.getSelectionModel().getSelectedItem() == target && result.complete(true)) {
                    Platform.runLater(stage::close);
                }
            };
            stage.focusedProperty().addListener((observable, oldValue, newValue) -> verify.run());
            notifier.notify("korTTY window activation test", "Click to restore the test window and select its tab.", () -> {
                activated.set(true);
                NotificationActivation.focus(target, null);
                verify.run();
                PauseTransition settle = new PauseTransition(Duration.seconds(3));
                settle.setOnFinished(event -> {
                    if (!result.isDone()) {
                        System.err.println("Window showing=" + stage.isShowing() + " iconified=" + stage.isIconified()
                            + " focused=" + stage.isFocused() + " targetSelected="
                            + (tabs.getSelectionModel().getSelectedItem() == target));
                        result.complete(false);
                    }
                    stage.close();
                });
                settle.play();
            });
        });
        try {
            if (!result.get(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Notification click did not restore and focus the target window/tab");
            }
            System.out.println("macNotificationClickSmoke OK: window restored, focused and target tab selected");
        } finally {
            notifier.close();
            Platform.runLater(Platform::exit);
        }
    }
}
