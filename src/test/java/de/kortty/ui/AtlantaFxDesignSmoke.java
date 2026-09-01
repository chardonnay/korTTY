package de.kortty.ui;

import de.kortty.model.AppDesign;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed integration smoke for the optional AtlantaFX Primer Dark app design.
 *
 * <p>The harness deliberately does not construct {@code KorTTYApplication}. It renders the four
 * JavaFX surface types the app has to keep in sync (a normal Scene, a DialogPane, a ContextMenu and
 * a raw Popup), exercises the package-private design-support overloads with an explicit design, and
 * verifies the real CSS cascade at 100% and 160%. Three screenshots are written below
 * {@code build/smoke}; run this class through the {@code atlantaFxDesignSmoke} Gradle task on a
 * machine with a display (or through {@code xvfb-run}).</p>
 */
public final class AtlantaFxDesignSmoke {

    private static final AppDesign ATLANTA = AppDesign.ATLANTAFX_PRIMER_DARK;
    private static final Color SNAPSHOT_FILL = Color.web("#0d1117");
    private static final Path OUTPUT_DIR = Path.of("build", "smoke");

    private AtlantaFxDesignSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-atlantafx-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("AtlantaFxDesignSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("AtlantaFxDesignSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("AtlantaFxDesignSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        Stage mainStage = null;
        Stage dialogStage = null;
        Stage transparentStage = null;
        ContextMenu contextMenu = null;
        Popup rawPopup = null;
        boolean continuationScheduled = false;
        try {
            verifyUserAgentSelectionIsIdempotent();
            AppDesignStyleSupport.applyUserAgentStylesheet(ATLANTA);
            requireEquals(AppDesignStyleSupport.desiredUserAgentStylesheet(ATLANTA),
                    Application.getUserAgentStylesheet(), "AtlantaFX user-agent stylesheet");

            String dynamicTheme = ThemeCssSupport.getDynamicStylesheetUrl(
                    new ThemeCssSupport.ThemeColors("#112233", "#ddeeff"));
            require(dynamicTheme != null, "Could not create the dynamic theme sentinel stylesheet");

            MainSurface main = buildMainSurface();
            mainStage = new Stage();
            mainStage.setTitle("korTTY — AtlantaFX smoke");
            mainStage.setScene(main.scene());
            mainStage.setX(80);
            mainStage.setY(80);
            registerAndApply(main.scene(), dynamicTheme, ATLANTA);
            mainStage.show();

            DialogSurface dialog = buildDialogSurface();
            dialogStage = new Stage(StageStyle.UTILITY);
            dialogStage.initOwner(mainStage);
            dialogStage.setTitle("Connection settings");
            dialogStage.setScene(dialog.scene());
            dialogStage.setX(mainStage.getX() + mainStage.getWidth() + 24);
            dialogStage.setY(mainStage.getY());
            registerAndApply(dialog.pane(), dynamicTheme, ATLANTA);
            dialogStage.show();

            contextMenu = new ContextMenu(
                    new MenuItem("Copy selection"),
                    new MenuItem("Paste command"),
                    new SeparatorMenuItem(),
                    new MenuItem("Open settings"));
            contextMenu.show(mainStage, mainStage.getX() + 180, mainStage.getY() + 150);
            Parent contextRoot = contextMenuRoot(contextMenu);
            registerAndApply(contextRoot, dynamicTheme, ATLANTA);

            PopupSurface popup = buildRawPopupSurface();
            rawPopup = new Popup();
            rawPopup.getContent().add(popup.root());
            rawPopup.setAutoHide(false);
            registerAndApply(popup.root(), dynamicTheme, ATLANTA);
            rawPopup.show(mainStage, mainStage.getX() + 420, mainStage.getY() + 260);

            TransparentSurface transparent = buildTransparentSurface();
            transparentStage = transparent.stage();
            registerAndApply(transparent.scene(), dynamicTheme, ATLANTA);
            transparentStage.setX(mainStage.getX() + 40);
            transparentStage.setY(mainStage.getY() + mainStage.getHeight() + 24);
            transparentStage.show();
            forceLayout(transparent.scene().getRoot());
            require(transparent.scene().lookup(".transparent-window-titlebar") != null,
                    "Transparent title bar was not installed");

            List<StylesheetSurface> surfaces = List.of(
                    new StylesheetSurface("main scene", main.scene().getStylesheets()),
                    new StylesheetSurface("dialog pane", dialog.pane().getStylesheets()),
                    new StylesheetSurface("context menu", contextRoot.getStylesheets()),
                    new StylesheetSurface("raw popup", popup.root().getStylesheets()),
                    new StylesheetSurface("transparent stage", transparent.scene().getStylesheets()));
            for (StylesheetSurface surface : surfaces) {
                assertAtlantaStyles(surface, dynamicTheme);
            }

            applyFontScale(main.scene(), dialog.pane(), contextRoot, popup.root(), 100);

            // Stage.show() returns before the first rendering pulse. Let one pulse paint the
            // controls before taking screenshots; otherwise a synchronous snapshot from this
            // startup callback contains only SnapshotParameters.fill.
            Stage shownMainStage = mainStage;
            Stage shownDialogStage = dialogStage;
            Stage shownTransparentStage = transparentStage;
            ContextMenu shownContextMenu = contextMenu;
            Popup shownRawPopup = rawPopup;
            PauseTransition settle = new PauseTransition(Duration.millis(300));
            settle.setOnFinished(ignored -> {
                try {
                    FontMeasurements atHundred = measureFonts(main, dialog, contextRoot, popup);
                    assertFontScale(atHundred, 100);

                    writeSnapshot(main.scene().getRoot(), OUTPUT_DIR.resolve("atlantafx-main.png"));
                    writeSnapshot(dialog.pane(), OUTPUT_DIR.resolve("atlantafx-dialog.png"));
                    writePopupSnapshot(contextRoot, popup.root(), OUTPUT_DIR.resolve("atlantafx-popup.png"));

                    applyFontScale(main.scene(), dialog.pane(), contextRoot, popup.root(), 160);
                    FontMeasurements atHundredSixty = measureFonts(main, dialog, contextRoot, popup);
                    assertFontScale(atHundredSixty, 160);
                    require(atHundredSixty.main() > atHundred.main() * 1.55,
                            "Rendered UI font did not grow from 100% to 160%");

                    // A second real application must not change the selected UAS or duplicate either
                    // author stylesheet on any currently open Window/Popup surface.
                    AppDesignStyleSupport.applyUserAgentStylesheet(ATLANTA);
                    registerAndApply(main.scene(), dynamicTheme, ATLANTA);
                    registerAndApply(dialog.pane(), dynamicTheme, ATLANTA);
                    registerAndApply(contextRoot, dynamicTheme, ATLANTA);
                    registerAndApply(popup.root(), dynamicTheme, ATLANTA);
                    for (StylesheetSurface surface : surfaces.subList(0, 4)) {
                        assertAtlantaStyles(surface, dynamicTheme);
                    }

                    AppDesignStyleSupport.applyUserAgentStylesheet(AppDesign.NORMAL);
                    // Exercise the production live-refresh path. It must reach Window Scenes as well
                    // as direct Parent surfaces such as hosted DialogPanes and raw Popup content.
                    AppDesignStyleSupport.applyToOpenWindows(AppDesign.NORMAL);
                    requireEquals(Application.STYLESHEET_MODENA, Application.getUserAgentStylesheet(),
                            "Modena user-agent stylesheet after Normal restore");
                    for (StylesheetSurface surface : surfaces) {
                        assertNormalStyles(surface);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, stack(error));
                } finally {
                    closeWindows(shownContextMenu, shownRawPopup, shownTransparentStage,
                            shownDialogStage, shownMainStage);
                    done.countDown();
                }
            });
            settle.play();
            continuationScheduled = true;
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            if (!continuationScheduled) {
                closeWindows(contextMenu, rawPopup, transparentStage, dialogStage, mainStage);
                done.countDown();
            }
        }
    }

    private static void closeWindows(
            ContextMenu contextMenu, Popup rawPopup, Stage transparentStage,
            Stage dialogStage, Stage mainStage) {
        if (contextMenu != null) {
            contextMenu.hide();
        }
        if (rawPopup != null) {
            rawPopup.hide();
        }
        if (transparentStage != null) {
            transparentStage.close();
        }
        if (dialogStage != null) {
            dialogStage.close();
        }
        if (mainStage != null) {
            mainStage.close();
        }
    }

    private static MainSurface buildMainSurface() {
        MenuBar menuBar = new MenuBar(
                new Menu("File"), new Menu("Session"), new Menu("View"), new Menu("Help"));
        ToolBar toolBar = new ToolBar(
                new Button("Connect"), new Button("New tab"), new Button("Settings"));

        Label welcome = new Label("Ready for a secure connection");
        welcome.getStyleClass().add("field-label");
        TextField host = new TextField("dev@example.test");
        host.setPromptText("user@host");
        Button open = new Button("Open session");
        open.setDefaultButton(true);
        VBox card = new VBox(12, new Label("Quick connect"), welcome, host, open);
        card.getStyleClass().add("logo-panel");
        card.setPadding(new Insets(24));
        card.setMaxWidth(440);

        TabPane tabs = new TabPane(new Tab("Dashboard", card), new Tab("Sessions", new Label("No sessions")));
        tabs.getTabs().forEach(tab -> tab.setClosable(false));

        Label status = new Label("Status: ready");
        status.getStyleClass().add("status-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region designCursor = new Region();
        designCursor.getStyleClass().add("app-design-cursor");
        HBox statusBar = new HBox(8, status, spacer, new Label("Primer Dark"), designCursor);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(7, 12, 7, 12));

        VBox top = new VBox(menuBar, toolBar);
        BorderPane root = new BorderPane(tabs, top, null, statusBar, null);
        root.getStyleClass().addAll("kortty-main-root", "main-content");
        Scene scene = new Scene(root, 780, 500);
        return new MainSurface(scene, status);
    }

    private static DialogSurface buildDialogSurface() {
        Label contentLabel = new Label("Connection name");
        contentLabel.getStyleClass().add("field-label");
        TextField name = new TextField("Development server");
        TextField host = new TextField("dev.example.test");
        VBox form = new VBox(8, contentLabel, name, new Label("Host"), host);
        form.setPadding(new Insets(8));

        DialogPane pane = new DialogPane();
        pane.setHeaderText("Edit connection");
        pane.setContent(form);
        pane.getButtonTypes().addAll(
                new ButtonType("Save", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        Scene scene = new Scene(pane, 560, 360);
        return new DialogSurface(scene, pane, contentLabel);
    }

    private static PopupSurface buildRawPopupSurface() {
        Label label = new Label("Command palette");
        label.getStyleClass().add("field-label");
        TextField command = new TextField("journal export --format markdown");
        Button run = new Button("Run");
        VBox root = new VBox(10, label, command, run);
        root.getStyleClass().add("logo-panel");
        root.setPadding(new Insets(14));
        root.setPrefSize(340, 140);
        return new PopupSurface(root, label);
    }

    private static TransparentSurface buildTransparentSurface() {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        Region titleBar = TransparentWindowChrome.buildTitleBar(stage, "Transparent terminal", stage::close);
        Label body = new Label("Transparent window chrome smoke");
        VBox root = new VBox(titleBar, body);
        root.getStyleClass().add("kortty-main-root");
        root.setSpacing(16);
        root.setPadding(new Insets(0, 16, 16, 16));
        Scene scene = new Scene(root, 500, 180, Color.TRANSPARENT);
        stage.setScene(scene);
        TransparentWindowChrome.installResize(stage, scene);
        return new TransparentSurface(stage, scene);
    }

    private static void registerAndApply(Scene scene, String dynamicTheme, AppDesign design) {
        AppDesignStyleSupport.registerApplicationBaseStyles(scene);
        addOnce(scene.getStylesheets(), dynamicTheme);
        AppDesignStyleSupport.applyToScene(scene, design);
        AppDesignStyleSupport.applyToScene(scene, design);
    }

    private static void registerAndApply(Parent root, String dynamicTheme, AppDesign design) {
        AppDesignStyleSupport.registerApplicationBaseStyles(root);
        addOnce(root.getStylesheets(), dynamicTheme);
        AppDesignStyleSupport.applyToParent(root, design);
        AppDesignStyleSupport.applyToParent(root, design);
    }

    private static void registerAndApply(DialogPane pane, String dynamicTheme, AppDesign design) {
        AppDesignStyleSupport.registerApplicationBaseStyles(pane);
        addOnce(pane.getStylesheets(), dynamicTheme);
        AppDesignStyleSupport.applyToDialogPane(pane, design);
        AppDesignStyleSupport.applyToDialogPane(pane, design);
    }

    private static void applyFontScale(
            Scene scene, DialogPane dialogPane, Parent contextRoot, Parent popupRoot, int percent) {
        UiFontScaleSupport.applyToStylesheets(scene.getStylesheets(), percent);
        UiFontScaleSupport.applyToStylesheets(dialogPane.getStylesheets(), percent);
        UiFontScaleSupport.applyToStylesheets(contextRoot.getStylesheets(), percent);
        UiFontScaleSupport.applyToStylesheets(popupRoot.getStylesheets(), percent);
        forceLayout(scene.getRoot());
        forceLayout(dialogPane);
        forceLayout(contextRoot);
        forceLayout(popupRoot);
    }

    private static FontMeasurements measureFonts(
            MainSurface main, DialogSurface dialog, Parent contextRoot, PopupSurface popup) {
        double context = fontSizeForText(contextRoot, "Copy selection");
        require(!Double.isNaN(context), "Could not find the rendered ContextMenu label");
        return new FontMeasurements(
                main.measureLabel().getFont().getSize(),
                dialog.measureLabel().getFont().getSize(),
                context,
                popup.measureLabel().getFont().getSize());
    }

    private static void assertFontScale(FontMeasurements actual, int percent) {
        double expected = UiFontScaleSupport.basePx(percent);
        checkFont("main", actual.main(), expected);
        checkFont("dialog", actual.dialog(), expected);
        checkFont("context menu", actual.contextMenu(), expected);
        checkFont("raw popup", actual.rawPopup(), expected);
        System.out.printf(Locale.ROOT,
                "  fonts @%d%% main=%.2f dialog=%.2f context=%.2f popup=%.2f%n",
                percent, actual.main(), actual.dialog(), actual.contextMenu(), actual.rawPopup());
    }

    private static void checkFont(String surface, double actual, double expected) {
        if (Double.isNaN(actual) || Math.abs(actual - expected) > 0.25) {
            throw new AssertionError(String.format(Locale.ROOT,
                    "%s font was %.2fpx, expected %.2fpx", surface, actual, expected));
        }
    }

    private static void assertAtlantaStyles(StylesheetSurface surface, String dynamicTheme) {
        String base = AppDesignStyleSupport.applicationBaseStylesheetUrl();
        String components = AppDesignStyleSupport.atlantaFxComponentsStylesheetUrl();
        String overlay = AppDesignStyleSupport.stylesheetUrl(ATLANTA);
        require(count(surface.stylesheets(), components) == 1,
                surface.name() + " must contain the AtlantaFX component sheet exactly once");
        require(count(surface.stylesheets(), overlay) == 1,
                surface.name() + " must contain the korTTY AtlantaFX overlay exactly once");
        require(count(surface.stylesheets(), base) == 0,
                surface.name() + " must not contain terminal.css while AtlantaFX is active");
        require(!surface.stylesheets().contains(dynamicTheme)
                        && surface.stylesheets().stream().noneMatch(ThemeCssSupport::isDynamicStylesheetUrl),
                surface.name() + " retained a dynamic terminal-theme stylesheet");
    }

    private static void assertNormalStyles(StylesheetSurface surface) {
        String base = AppDesignStyleSupport.applicationBaseStylesheetUrl();
        String components = AppDesignStyleSupport.atlantaFxComponentsStylesheetUrl();
        String overlay = AppDesignStyleSupport.stylesheetUrl(ATLANTA);
        require(count(surface.stylesheets(), base) == 1,
                surface.name() + " did not restore terminal.css exactly once");
        require(count(surface.stylesheets(), components) == 0,
                surface.name() + " retained the AtlantaFX component sheet after Normal restore");
        require(count(surface.stylesheets(), overlay) == 0,
                surface.name() + " retained the AtlantaFX overlay after Normal restore");
    }

    private static void verifyUserAgentSelectionIsIdempotent() {
        AtomicReference<String> current = new AtomicReference<>(Application.STYLESHEET_MODENA);
        AtomicInteger writes = new AtomicInteger();
        boolean first = AppDesignStyleSupport.updateUserAgentStylesheet(
                ATLANTA, current::get, value -> {
                    writes.incrementAndGet();
                    current.set(value);
                });
        boolean second = AppDesignStyleSupport.updateUserAgentStylesheet(
                ATLANTA, current::get, value -> {
                    writes.incrementAndGet();
                    current.set(value);
                });
        require(first, "Initial Primer selection must update the user-agent stylesheet");
        require(!second && writes.get() == 1,
                "Repeated Primer selection must be an idempotent no-op");
    }

    private static Parent contextMenuRoot(ContextMenu contextMenu) {
        Node skinNode = contextMenu.getSkin() != null ? contextMenu.getSkin().getNode() : null;
        require(skinNode instanceof Parent, "ContextMenu did not create a Parent skin root");
        return (Parent) skinNode;
    }

    private static void writeSnapshot(Node node, Path output) throws Exception {
        forceLayout(node);
        BufferedImage image = snapshot(node);
        writeImage(image, output);
    }

    private static void writePopupSnapshot(Node contextRoot, Node popupRoot, Path output) throws Exception {
        forceLayout(contextRoot);
        forceLayout(popupRoot);
        BufferedImage context = snapshot(contextRoot);
        BufferedImage popup = snapshot(popupRoot);
        int gap = 18;
        int width = context.getWidth() + popup.getWidth() + gap;
        int height = Math.max(context.getHeight(), popup.getHeight());
        BufferedImage combined = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = combined.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(13, 17, 23));
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(context, 0, 0, null);
            graphics.drawImage(popup, context.getWidth() + gap, 0, null);
        } finally {
            graphics.dispose();
        }
        writeImage(combined, output);
    }

    private static BufferedImage snapshot(Node node) {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(SNAPSHOT_FILL);
        WritableImage image = node.snapshot(parameters, null);
        return SwingFXUtils.fromFXImage(image, null);
    }

    private static void writeImage(BufferedImage image, Path output) throws Exception {
        assertVisualVariation(image, output.getFileName().toString());
        Files.createDirectories(output.getParent());
        require(ImageIO.write(image, "png", output.toFile()), "No PNG writer available");
        System.out.println("  snapshot: " + output.toAbsolutePath());
    }

    private static void assertVisualVariation(BufferedImage image, String name) {
        HashSet<Integer> colors = new HashSet<>();
        int stepX = Math.max(1, image.getWidth() / 100);
        int stepY = Math.max(1, image.getHeight() / 60);
        for (int y = 0; y < image.getHeight() && colors.size() < 12; y += stepY) {
            for (int x = 0; x < image.getWidth() && colors.size() < 12; x += stepX) {
                colors.add(image.getRGB(x, y));
            }
        }
        require(colors.size() >= 8,
                name + " contains too little visual variation; the JavaFX surface was likely not painted");
    }

    private static void forceLayout(Node node) {
        if (node instanceof Parent parent) {
            parent.applyCss();
            parent.layout();
        }
    }

    private static double fontSizeForText(Node node, String expectedText) {
        if (node instanceof Label label && expectedText.equals(label.getText())) {
            return label.getFont().getSize();
        }
        if (node instanceof Text text && expectedText.equals(text.getText())) {
            return text.getFont().getSize();
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                double result = fontSizeForText(child, expectedText);
                if (!Double.isNaN(result)) {
                    return result;
                }
            }
        }
        return Double.NaN;
    }

    private static void addOnce(ObservableList<String> stylesheets, String stylesheet) {
        if (stylesheet != null && !stylesheets.contains(stylesheet)) {
            stylesheets.add(stylesheet);
        }
    }

    private static long count(ObservableList<String> stylesheets, String stylesheet) {
        return stylesheet == null ? 0 : stylesheets.stream().filter(stylesheet::equals).count();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireEquals(Object expected, Object actual, String what) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(what + " was " + actual + ", expected " + expected);
        }
    }

    private static String stack(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private record MainSurface(Scene scene, Label measureLabel) {
    }

    private record DialogSurface(Scene scene, DialogPane pane, Label measureLabel) {
    }

    private record PopupSurface(VBox root, Label measureLabel) {
    }

    private record TransparentSurface(Stage stage, Scene scene) {
    }

    private record StylesheetSurface(String name, ObservableList<String> stylesheets) {
    }

    private record FontMeasurements(double main, double dialog, double contextMenu, double rawPopup) {
    }
}
