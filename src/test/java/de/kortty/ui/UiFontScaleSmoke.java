package de.kortty.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proves the UI font scale actually reaches rendered text, which no unit test can: the pure tests
 * check that the right CSS is produced, not that JavaFX's cascade resolves it onto a node.
 *
 * <p>Measures the computed font of a menu-bar title, a plain label and a context-menu item at 100%
 * and at 160%. The context-menu item is the point of the exercise — its popup does not inherit the
 * font of the menu bar that opened it, so if the {@code .context-menu} anchor were missing, the
 * menu titles would grow while the items in the dropdown stayed exactly as they are.</p>
 */
public final class UiFontScaleSmoke {

    private static final double BASE = UiFontScaleSupport.BASE_FONT_PX;

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-ui-font-scale-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("UiFontScaleSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("UiFontScaleSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("UiFontScaleSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        try {
            MenuItem menuItem = new MenuItem("Preferences");
            Menu menu = new Menu("File");
            menu.getItems().add(menuItem);
            MenuBar menuBar = new MenuBar(menu);

            Label plainLabel = new Label("Plain label");
            Label smallLabel = new Label("Caption");
            // The value 11px converts to under the em migration — the most common one in the app.
            smallLabel.setStyle("-fx-font-size: 0.8462em;");

            ContextMenu contextMenu = new ContextMenu(new MenuItem("Copy"));

            VBox root = new VBox(menuBar, plainLabel, smallLabel);
            Scene scene = new Scene(root, 400, 200);
            var baseCss = UiFontScaleSmoke.class.getResource("/styles/terminal.css");
            if (baseCss == null) {
                failure.set("terminal.css missing from the classpath");
                done.countDown();
                return;
            }
            scene.getStylesheets().add(baseCss.toExternalForm());

            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            contextMenu.show(stage);

            List<String> problems = new ArrayList<>();
            double[] atHundred = measure(scene, contextMenu, 100);
            double[] atMax = measure(scene, contextMenu, 160);

            String[] names = {"menu-bar title", "plain label", "0.8462em caption", "context-menu item"};
            // 160% of the 13px base. The caption carries its own em factor on top.
            double[] expectedHundred = {BASE, BASE, BASE * 0.8462, BASE};
            double[] expectedMax = {BASE * 1.6, BASE * 1.6, BASE * 1.6 * 0.8462, BASE * 1.6};

            for (int i = 0; i < names.length; i++) {
                report(names[i], atHundred[i], atMax[i]);
                check(problems, names[i] + " @100%", atHundred[i], expectedHundred[i]);
                check(problems, names[i] + " @160%", atMax[i], expectedMax[i]);
            }

            contextMenu.hide();
            stage.close();

            if (!problems.isEmpty()) {
                failure.set(String.join("; ", problems));
            }
        } catch (Exception e) {
            failure.set(e.toString());
        } finally {
            done.countDown();
        }
    }

    /** @return the computed font size of the menu title, plain label, caption and context-menu item. */
    private static double[] measure(Scene scene, ContextMenu contextMenu, int percent) {
        UiFontScaleSupport.applyToStylesheets(scene.getStylesheets(), percent);
        applyToContextMenu(contextMenu, percent);
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        VBox root = (VBox) scene.getRoot();
        return new double[] {
            fontSizeOf(root.getChildren().get(0)),                     // MenuBar -> its menu title
            ((Label) root.getChildren().get(1)).getFont().getSize(),
            ((Label) root.getChildren().get(2)).getFont().getSize(),
            contextMenuItemFontSize(contextMenu),
        };
    }

    /**
     * A ContextMenu is not in the scene graph, so the scene's stylesheet list does not reach it;
     * its own skin node carries the sheets instead. Mirrors what applyToParent does for raw popups.
     */
    private static void applyToContextMenu(ContextMenu contextMenu, int percent) {
        Node skin = contextMenu.getSkin() != null ? contextMenu.getSkin().getNode() : null;
        if (skin instanceof Parent parent) {
            UiFontScaleSupport.applyToStylesheets(parent.getStylesheets(), percent);
            parent.applyCss();
        }
    }

    private static double contextMenuItemFontSize(ContextMenu contextMenu) {
        Node skin = contextMenu.getSkin() != null ? contextMenu.getSkin().getNode() : null;
        if (skin == null) {
            return Double.NaN;
        }
        return firstTextSize(skin);
    }

    /** Walks the skin for the first rendered Text/Label, which is where the resolved font shows up. */
    private static double firstTextSize(Node node) {
        if (node instanceof Text text) {
            return text.getFont().getSize();
        }
        if (node instanceof Label label) {
            return label.getFont().getSize();
        }
        if (node instanceof Parent parent) {
            for (Node child : childrenOf(parent)) {
                double size = firstTextSize(child);
                if (!Double.isNaN(size)) {
                    return size;
                }
            }
        }
        return Double.NaN;
    }

    private static double fontSizeOf(Node node) {
        double size = firstTextSize(node);
        return Double.isNaN(size) ? -1 : size;
    }

    private static ObservableList<Node> childrenOf(Parent parent) {
        return parent.getChildrenUnmodifiable() != null
            ? FXCollections.observableArrayList(parent.getChildrenUnmodifiable())
            : FXCollections.observableArrayList();
    }

    private static void report(String what, double atHundred, double atMax) {
        System.out.printf(Locale.ROOT, "  %-20s 100%%=%.2fpx  160%%=%.2fpx%n", what, atHundred, atMax);
    }

    private static void check(List<String> problems, String what, double actual, double expected) {
        if (Double.isNaN(actual) || Math.abs(actual - expected) > 0.15) {
            problems.add(String.format(Locale.ROOT, "%s was %.2fpx, expected %.2fpx", what, actual, expected));
        }
    }
}
