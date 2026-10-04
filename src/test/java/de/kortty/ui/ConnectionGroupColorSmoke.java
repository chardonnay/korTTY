package de.kortty.ui;

import de.kortty.core.ConnectionGroupColors;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of folder (group) tab colors in the Connection Manager: a folder with a color of
 * its own shows a dot of that color after its name, with a tooltip and screen-reader text that name it,
 * a folder without one shows none, the folder menu offers "Tab Color...", and the color dialog starts
 * unticked for a folder that only inherits a color, names where that color comes from, and returns
 * the picked color on OK, no color when unticked and nothing on Cancel. Pass a directory via --args to
 * also save PNG snapshots of the tree and the dialog. Run via the {@code connectionGroupColorSmoke}
 * Gradle task. Exit 0 = OK.
 */
public final class ConnectionGroupColorSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;

    private static final String RED = "#D32F2F";

    private ConnectionGroupColorSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        String snapshotDir = args.length > 0 ? args[0] : null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        GlobalSettings settings = new GlobalSettings();
        settings.setConnectionGroupColor("Production", RED);
        List<GroupPath> edited = new ArrayList<>();

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                ConnectionManagerTreeView tree = new ConnectionManagerTreeView(FXCollections.observableArrayList(
                    connection("db-1", "Production/DB"), connection("web-1", "Production"),
                    connection("test-1", "Test")));
                tree.setGroupColorProbe(group -> settings.getConnectionGroupColor(group.getPath()));
                tree.setOnEditGroupColor(edited::add);
                tree.refreshTree();
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(tree, 420, 260));
                stage.show();
                Thread worker = new Thread(() -> verify(tree, settings, edited, snapshotDir, failure, done),
                    "group-color-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.runLater(() -> {
            try {
                if (stageRef.get() != null) stageRef.get().close();
            } catch (Exception ignored) {
            }
            Platform.exit();
        });
        if (!finished) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SMOKE FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK: a colored folder shows a named dot, its menu offers Tab Color..., and the"
            + " dialog names the inherited color and returns the choice");
        System.exit(0);
    }

    private static void verify(ConnectionManagerTreeView tree, GlobalSettings settings, List<GroupPath> edited,
                               String snapshotDir, AtomicReference<String> failure, CountDownLatch done) {
        try {
            settle();
            onFxThread(() -> {
                TreeCell<?> production = groupCell(tree, "Production");
                TreeCell<?> test = groupCell(tree, "Test");
                check(production != null && test != null, "the folder rows are not shown");
                check(production.getGraphic() instanceof Circle, "the colored folder shows no dot: " + production.getGraphic());
                Circle dot = (Circle) production.getGraphic();
                check(Color.web(RED).equals(dot.getFill()), "the dot is " + dot.getFill() + ", not the folder's red");
                String expected = I18n.get("connManager.group.tabColor.swatch", I18n.get("tab.tooltip.color.red"), RED);
                check(expected.equals(dot.getAccessibleText()), "the dot reads '" + dot.getAccessibleText() + "'");
                check(production.getTooltip() != null && expected.equals(production.getTooltip().getText()),
                    "the colored folder has no tooltip naming its color");
                check(production.getAccessibleText() != null && production.getAccessibleText().contains(expected)
                    && production.getAccessibleText().contains("Production"),
                    "the folder row reads '" + production.getAccessibleText() + "'");
                check(test.getGraphic() == null && test.getTooltip() == null, "a folder without a color shows one");
                TreeCell<?> subfolder = groupCell(tree, "DB");
                check(subfolder != null && subfolder.getGraphic() == null,
                    "a subfolder that only inherits the color shows a dot of its own");

                ContextMenu menu = production.getContextMenu();
                check(menu != null, "the folder has no context menu");
                MenuItem colorItem = menu.getItems().stream()
                    .filter(item -> I18n.get("connManager.group.tabColor").equals(item.getText()))
                    .findFirst().orElse(null);
                check(colorItem != null, "the folder menu has no Tab Color... entry");
                colorItem.fire();
                check(edited.size() == 1 && edited.get(0).equals(new GroupPath("Production")),
                    "Tab Color... did not hand over the folder: " + edited);
                snapshot(tree.getScene(), snapshotDir, "connection-manager-folder-color.png");
                return null;
            });

            onFxThread(() -> {
                ConnectionGroupColorDialog dialog = new ConnectionGroupColorDialog(
                    new GroupPath("Production/DB"), settings.getConnectionGroupColors());
                VBox content = (VBox) dialog.getDialogPane().getContent();
                CheckBox enable = (CheckBox) content.lookup(".check-box");
                ColorPicker picker = (ColorPicker) content.lookup(".color-picker");
                check(enable != null && picker != null, "the dialog has no check box or color picker");
                check(!enable.isSelected(), "a folder that only inherits its color starts ticked");
                check(picker.isDisabled(), "the picker is usable without the tick");
                check(Color.web(RED).equals(picker.getValue()), "the picker does not start at the inherited color");
                String inherited = I18n.get("connManager.group.tabColor.inherited",
                    I18n.get("tab.tooltip.color.red"), RED, "Production");
                check(content.getChildren().stream().anyMatch(node -> node instanceof Label label
                    && inherited.equals(label.getText())), "the dialog does not name the inherited color");

                check(dialog.getResultConverter().call(ButtonType.CANCEL) == null, "Cancel returned a choice");
                ConnectionGroupColorDialog.Choice none = dialog.getResultConverter().call(ButtonType.OK);
                check(none != null && none.color() == null, "OK without the tick returned " + none);
                enable.setSelected(true);
                picker.setValue(Color.web("#1976D2"));
                ConnectionGroupColorDialog.Choice blue = dialog.getResultConverter().call(ButtonType.OK);
                check(blue != null && "#1976D2".equals(blue.color()), "OK with blue returned " + blue);

                ConnectionGroupColorDialog own = new ConnectionGroupColorDialog(
                    new GroupPath("Production"), settings.getConnectionGroupColors());
                CheckBox ownEnable = (CheckBox) own.getDialogPane().getContent().lookup(".check-box");
                check(ownEnable.isSelected(), "a folder with a color of its own starts unticked");
                check(ConnectionGroupColors.inheritedFromAbove("Production", settings.getConnectionGroupColors()::get) == null,
                    "a top-level folder names a color from above");

                dialog.show();
                snapshot(dialog.getDialogPane().getScene(), snapshotDir, "connection-manager-folder-color-dialog.png");
                dialog.close();
                return null;
            });
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    private static ServerConnection connection(String name, String group) {
        ServerConnection connection = new ServerConnection(name, name + ".example.com", 22, "demo");
        connection.setGroup(group);
        return connection;
    }

    private static TreeCell<?> groupCell(ConnectionManagerTreeView tree, String name) {
        for (Node node : tree.lookupAll(".tree-cell")) {
            if (node instanceof TreeCell<?> cell && cell.getItem() instanceof ConnectionTreeItem.ItemData data
                && data.isGroup() && data.getGroupPath().getName().equals(name)) {
                return cell;
            }
        }
        return null;
    }

    private static void snapshot(Scene scene, String directory, String fileName) throws Exception {
        if (directory == null) {
            return;
        }
        WritableImage image = scene.snapshot(null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(directory, fileName));
    }

    /** Two pulses, so the tree has laid out its cells. */
    private static void settle() throws Exception {
        onFxThread(() -> null);
        Thread.sleep(200);
        onFxThread(() -> null);
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw error;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String stack(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
