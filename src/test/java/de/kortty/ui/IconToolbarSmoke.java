package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.IconToolbarSettings;
import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import de.kortty.model.ServerConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/** Live integration smoke with the real window and isolated demo configuration; no test doubles. */
public final class IconToolbarSmoke {
    private IconToolbarSmoke() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("kortty-toolbar-smoke").toString());
        Locale.setDefault(args.length > 0 && "de".equals(args[0]) ? Locale.GERMAN : Locale.ENGLISH);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { failure.set(error); done.countDown(); });
        Platform.startup(() -> {
            try {
                exercise(done, failure);
            } catch (Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.exit();
        if (failure.get() != null) { failure.get().printStackTrace(); System.exit(1); }
        if (!finished) { System.err.println("Toolbar smoke timed out"); System.exit(2); }
        System.out.println("ICON TOOLBAR SMOKE PASSED");
        System.exit(0);
    }

    private static void exercise(CountDownLatch done, AtomicReference<Throwable> failure) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { failure.set(error); done.countDown(); });
        var global = app.getGlobalSettingsManager().getSettings();
        global.setLanguage(Locale.getDefault().getLanguage());
        global.setAppDesign(AppDesign.NORMAL);
        LanguageManager.getInstance().initialize(global);
        ServerConnection server = new ServerConnection("Demo-Server", "demo.invalid", 22, "demo");
        server.setGroup("Demo-Gruppe");
        app.getConfigManager().addConnection(server);
        IconToolbarSettings configuration = IconToolbarSettings.defaults();
        List<Entry> entries = new ArrayList<>(configuration.entries());
        entries.add(Entry.create(Kind.CONNECTION, Section.CONNECTIONS, server.getId()));
        entries.add(Entry.create(Kind.GROUP, Section.CONNECTIONS, "Demo-Gruppe"));
        configuration.setEntries(entries);
        global.setIconToolbar(configuration);

        Stage stage = new Stage();
        new MainWindow(stage);
        stage.setWidth(1160);
        stage.setHeight(700);
        stage.show();
        Parent root = stage.getScene().getRoot();
        layout(root);
        IconToolbar toolbar = (IconToolbar) root.lookup(".icon-toolbar");
        check(toolbar != null && toolbar.isVisible(), "Toolbar visible on fresh window");
        Path output = Files.createDirectories(Path.of("build/smoke/icon-toolbar"
            + (Locale.GERMAN.getLanguage().equals(Locale.getDefault().getLanguage()) ? "-de" : "")));
        snapshot(root, output.resolve("main-window.png"));
        snapshot(toolbar, output.resolve("normal.png"));
        checkMenuBarToggle(stage, toolbar, output);
        checkStableButtonFocus(stage, toolbar);
        int initialTabs = ((TabPane) root.lookup(".tab-pane")).getTabs().size();
        toolbar.beginEditing();
        layout(root);
        snapshot(toolbar, output.resolve("customize.png"));
        checkCatalogRows(stage, toolbar);
        snapshot(toolbar, output.resolve("settings-customize.png"));
        // Clicking a destination during editing opens editing options, never a connection.
        Button connectionButton = button(toolbar, "Demo-Server");
        connectionButton.fire();
        check(((TabPane) root.lookup(".tab-pane")).getTabs().size() == initialTabs, "Editing must not connect");
        javafx.stage.Window.getWindows().stream().filter(window -> window instanceof javafx.stage.PopupWindow)
            .toList().forEach(javafx.stage.Window::hide);
        button(toolbar, I18n.get("toolbar.cancel")).fire();
        check(global.getIconToolbar().entries().size() == entries.size(), "Cancel preserves layout");

        // Settings navigation uses identities, even after the tabs are reordered.
        SettingsDialog dialog = new SettingsDialog(stage, app, app.getConfigManager(), global,
            app.getCredentialManager(), app.getGpgKeyManager());
        var field = SettingsDialog.class.getDeclaredField("mainTabPane");
        field.setAccessible(true);
        TabPane settingsTabs = (TabPane) field.get(dialog);
        var reversed = new ArrayList<>(settingsTabs.getTabs());
        java.util.Collections.reverse(reversed);
        settingsTabs.getTabs().setAll(reversed);
        for (SettingsSection section : SettingsSection.values()) {
            dialog.selectSection(section);
            check(settingsTabs.getSelectionModel().getSelectedItem().getUserData() == section,
                "Settings destination " + section);
        }
        java.util.Collections.reverse(reversed);
        settingsTabs.getTabs().setAll(reversed);
        var sizeField = SettingsDialog.class.getDeclaredField("toolbarIconSizeSlider");
        sizeField.setAccessible(true);
        var sizeSlider = (javafx.scene.control.Slider) sizeField.get(dialog);
        check(sizeSlider.getValue() == 24, "Window settings show the compatible default icon size");
        var applySettings = SettingsDialog.class.getDeclaredMethod("applySettings");
        applySettings.setAccessible(true);
        sizeSlider.setValue(32);
        check(Boolean.TRUE.equals(applySettings.invoke(dialog)), "Apply Window settings");
        check(global.getToolbarIconSize() == 32, "Slider icon size is committed when saving settings");
        sizeSlider.setValue(24);
        check(Boolean.TRUE.equals(applySettings.invoke(dialog)), "Restore default Window settings");
        dialog.selectSection(SettingsSection.WINDOW);
        dialog.show();
        layout(dialog.getDialogPane());
        var preview = (javafx.scene.layout.Region) dialog.getDialogPane().lookup(".toolbar-icon-size-preview");
        sizeSlider.setValue(48);
        layout(dialog.getDialogPane());
        check(preview.getWidth() == 48 && preview.getHeight() == 48, "Slider updates the visible preview immediately");
        var drawingBounds = preview.getChildrenUnmodifiable().getFirst().getBoundsInParent();
        check(drawingBounds.getMinX() >= -0.5 && drawingBounds.getMinY() >= -0.5
            && drawingBounds.getMaxX() <= 48.5 && drawingBounds.getMaxY() <= 48.5,
            "Scaled preview fits its canvas without shifting or clipping");
        check(global.getToolbarIconSize() == 24, "Preview alone does not change saved icon size");
        sizeSlider.setValue(24);
        layout(dialog.getDialogPane());
        snapshot(dialog.getDialogPane(), output.resolve("window-settings.png"));
        sizeSlider.setValue(48);
        dialog.close();
        check(global.getToolbarIconSize() == 24, "Cancelling the preview preserves the saved icon size");

        // Theme contrast in both modes, and actual native overflow at a narrow width.
        for (AppDesign design : List.of(AppDesign.ATLANTAFX_PRIMER_LIGHT, AppDesign.ATLANTAFX_PRIMER_DARK)) {
            global.setAppDesign(design);
            AppDesignStyleSupport.applyUserAgentStylesheet(design);
            AppDesignStyleSupport.applyToScene(stage.getScene());
            toolbar.applyTheme(AppDesignStyleSupport.activeBackgroundColor(), AppDesignStyleSupport.activeTextColor());
            layout(root);
            snapshot(toolbar, output.resolve(design.name().toLowerCase(Locale.ROOT) + ".png"));
            checkStableButtonFocus(stage, toolbar);
            toolbar.beginEditing();
            layout(root);
            checkCatalogRows(stage, toolbar);
            snapshot(toolbar, output.resolve(design.name().toLowerCase(Locale.ROOT) + "-customize.png"));
            button(toolbar, I18n.get("toolbar.cancel")).fire();
        }
        stage.setMinWidth(0);
        stage.setWidth(380);
        ((javafx.scene.layout.Region) root).resize(380, 700);
        layout(root);
        snapshot(toolbar, output.resolve("narrow.png"));
        check(toolbar.lookupAll(".tool-bar-overflow-button").stream().anyMatch(Node::isVisible), "Overflow remains accessible");

        // Missing action/connection references remain visible but disabled.
        var missing = new IconToolbarSettings();
        missing.setEntries(List.of(Entry.action("missing.action", Section.TOOLS),
            Entry.create(Kind.CONNECTION, Section.CONNECTIONS, "deleted-id")));
        toolbar.reload(missing);
        layout(root);
        check(toolbar.lookupAll(".toolbar-action").stream().allMatch(Node::isDisabled), "Missing targets disabled");
        toolbar.beginEditing();
        layout(root);
        check(toolbar.lookupAll(".toolbar-action").stream().noneMatch(Node::isDisabled), "Missing targets removable in editor");
        button(toolbar, I18n.get("toolbar.cancel")).fire();
        toolbar.reload(configuration);

        // Exercise the one-click local-shell action through the real registry (no remote traffic).
        ((javafx.scene.layout.Region) root).resize(1160, 700);
        layout(root);
        Button localShell = toolbar.lookupAll(".toolbar-action").stream().filter(Button.class::isInstance)
            .map(Button.class::cast).filter(button -> I18n.get("toolbar.localShell").equals(button.getAccessibleText()))
            .findFirst().orElseThrow();
        localShell.fire();
        TabPane mainTabs = (TabPane) root.lookup(".tab-pane");
        check(mainTabs.getTabs().size() == initialTabs + 1, "Local-shell action opens exactly one tab");
        TerminalTab local = (TerminalTab) mainTabs.getSelectionModel().getSelectedItem();
        check(local.getConnection().isLocalShell(), "Local-shell protocol selected");

        // Global visibility changes reach a second real window and survive a settings reload.
        Stage second = new Stage();
        new MainWindow(second);
        exerciseIconSizes(stage, second);
        exerciseVisibility(stage, second, configuration, 0, () -> {
            local.releaseResources();
            mainTabs.getTabs().remove(local);
            exerciseFirstClick(stage, second, toolbar, mainTabs, done);
        });
    }

    /** Every combination is saved, read back from disk, and applied to both real windows. */
    private static void exerciseVisibility(Stage first, Stage second, IconToolbarSettings configuration,
                                          int combination, Runnable finished) {
        if (combination == 4) { finished.run(); return; }
        boolean actions = (combination & 1) != 0;
        boolean connections = (combination & 2) != 0;
        configuration.setVisible(actions);
        configuration.setConnectionsVisible(connections);
        MainWindow.saveIconToolbarSettings(configuration, saved -> {
            check(saved, "Save independent toolbar visibility");
            for (Stage window : List.of(first, second)) {
                IconToolbar toolbar = (IconToolbar) window.getScene().getRoot().lookup(".icon-toolbar");
                check(toolbar.isVisible() == (actions || connections), "Toolbar container visibility");
                check((toolbar.lookup(".toolbar-tasks") != null) == actions, "Action row visibility");
                check((toolbar.lookup(".toolbar-connections") != null) == connections, "Connection row visibility");
                if (window.isShowing() && (actions || connections)) {
                    layout(window.getScene().getRoot());
                    checkVisibilityMenu(toolbar, actions, connections);
                }
                if (actions != connections) {
                    toolbar.beginEditing();
                    check(toolbar.lookup(".toolbar-tasks") != null && toolbar.lookup(".toolbar-connections") != null,
                        "Both rows available while customizing");
                    button(toolbar, I18n.get("toolbar.cancel")).fire();
                    check((toolbar.lookup(".toolbar-tasks") != null) == actions, "Cancel preserves action visibility");
                    check((toolbar.lookup(".toolbar-connections") != null) == connections, "Cancel preserves connection visibility");
                }
            }
            try {
                var stored = new de.kortty.core.GlobalSettingsManager(KorTTYApplication.getConfigDirectory());
                stored.load();
                check(stored.getSettings().getIconToolbar().isVisible() == actions, "Action visibility persisted");
                check(stored.getSettings().getIconToolbar().isConnectionsVisible() == connections, "Connection visibility persisted");
            } catch (Exception error) {
                throw new IllegalStateException("Read back toolbar settings", error);
            }
            exerciseVisibility(first, second, configuration, combination + 1, finished);
        });
    }

    private static void exerciseIconSizes(Stage first, Stage second) {
        var global = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        for (AppDesign design : List.of(AppDesign.NORMAL, AppDesign.ATLANTAFX_PRIMER_LIGHT, AppDesign.ATLANTAFX_PRIMER_DARK)) {
            global.setAppDesign(design);
            AppDesignStyleSupport.applyUserAgentStylesheet(design);
            for (Stage window : List.of(first, second)) AppDesignStyleSupport.applyToScene(window.getScene());
            for (int size : List.of(16, 32, 64)) {
                global.setToolbarIconSize(size);
                MainWindow.refreshIconToolbarSizes();
                for (Stage window : List.of(first, second)) {
                    var root = (javafx.scene.layout.Region) window.getScene().getRoot();
                    root.resize(1160, 700);
                    layout(root);
                    var toolbar = (IconToolbar) root.lookup(".icon-toolbar");
                    toolbar.lookupAll(".toolbar-action").stream().filter(Button.class::isInstance)
                        .map(Button.class::cast).forEach(button -> {
                            var graphic = (javafx.scene.layout.Region) button.getGraphic();
                            check(graphic.prefWidth(-1) == size && graphic.prefHeight(-1) == size,
                                "Icon size applied to both real windows: " + size);
                        });
                    if (window.isShowing()) {
                        toolbar.beginEditing();
                        checkCatalogRows(window, toolbar);
                        button(toolbar, I18n.get("toolbar.cancel")).fire();
                        checkStableButtonFocus(window, toolbar);
                    }
                }
            }
        }
        global.setToolbarIconSize(24);
        MainWindow.refreshIconToolbarSizes();
    }

    private static void checkVisibilityMenu(IconToolbar toolbar, boolean actions, boolean connections) {
        var menu = toolbarContextMenu(toolbar);
        try {
            for (var item : menu.getItems()) {
                if (item instanceof javafx.scene.control.CheckMenuItem toggle) {
                    boolean expected;
                    if (I18n.get("menu.view.iconToolbar").equals(toggle.getText())) {
                        expected = actions;
                    } else if (I18n.get("menu.view.connectionToolbar").equals(toggle.getText())) {
                        expected = connections;
                    } else {
                        check(I18n.get("toolbar.menuBar").equals(toggle.getText()), "Known toolbar visibility switch");
                        expected = windowMenuBar((Stage) toolbar.getScene().getWindow()).isVisible();
                    }
                    check(toggle.isSelected() == expected, "Context menu reflects current visibility: " + toggle.getText());
                }
            }
        } finally {
            menu.hide();
        }
    }

    private static javafx.scene.control.ContextMenu toolbarContextMenu(IconToolbar toolbar) {
        return toolbarContextMenu(toolbar, toolbar);
    }

    private static javafx.scene.control.ContextMenu toolbarContextMenu(IconToolbar toolbar, Node target) {
        var location = target.localToScreen(5, 5);
        target.fireEvent(new javafx.scene.input.ContextMenuEvent(
            javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
            5, 5, location.getX(), location.getY(), false, null));
        return javafx.stage.Window.getWindows().stream()
            .filter(javafx.scene.control.ContextMenu.class::isInstance)
            .map(javafx.scene.control.ContextMenu.class::cast)
            .filter(popup -> popup.getOwnerNode() == toolbar).findFirst().orElseThrow();
    }

    private static javafx.scene.control.MenuBar windowMenuBar(Stage stage) {
        return stage.getScene().getRoot().lookupAll(".menu-bar").stream()
            .filter(javafx.scene.control.MenuBar.class::isInstance)
            .map(javafx.scene.control.MenuBar.class::cast).filter(bar -> !bar.isUseSystemMenuBar())
            .findFirst().orElseThrow();
    }

    private static void checkMenuBarToggle(Stage stage, IconToolbar toolbar, Path output) throws Exception {
        var bar = windowMenuBar(stage);
        check(!bar.isVisible() && !bar.isManaged(), "New windows start with the menu bar hidden");
        var viewToggle = bar.getMenus().stream().flatMap(menu -> menu.getItems().stream())
            .filter(javafx.scene.control.CheckMenuItem.class::isInstance)
            .map(javafx.scene.control.CheckMenuItem.class::cast)
            .filter(item -> I18n.get("menu.view.menuBar").equals(item.getText())).findFirst().orElseThrow();
        for (boolean expected : List.of(false, true)) {
            Node row = toolbar.lookup(expected ? ".toolbar-connections" : ".toolbar-tasks");
            var menu = toolbarContextMenu(toolbar, row);
            try {
                var toggle = menu.getItems().stream().filter(javafx.scene.control.CheckMenuItem.class::isInstance)
                    .map(javafx.scene.control.CheckMenuItem.class::cast)
                    .filter(item -> I18n.get("toolbar.menuBar").equals(item.getText())).findFirst().orElseThrow();
                check(!toggle.isDisable(), "Menu bar switch remains available while hidden");
                check(toggle.isSelected() == expected, "Toolbar context menu reads the current menu bar state");
                if (!expected) {
                    layout(menu.getScene().getRoot());
                    snapshot(menu.getScene().getRoot(), output.resolve("context-menu.png"));
                }
                de.kortty.ui.actions.MenuItemActivation.activate(toggle);
                layout(stage.getScene().getRoot());
                check(bar.isVisible() == !expected && bar.isManaged() == !expected,
                    "Toolbar context menu shows and hides the menu bar immediately");
                check(viewToggle.isSelected() == !expected, "View menu stays synchronized with the toolbar switch");
                check(toolbar.lookup(".toolbar-tasks") != null && toolbar.lookup(".toolbar-connections") != null,
                    "Menu bar visibility does not change either toolbar row");
            } finally {
                menu.hide();
            }
        }
    }

    /** Test-only focus-event injection reproduces a focus change between mouse press and release. */
    private static void exerciseFirstClick(Stage stage, Stage second, IconToolbar toolbar,
                                           TabPane tabs, CountDownLatch done) {
        KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings().setAppDesign(AppDesign.NORMAL);
        AppDesignStyleSupport.applyUserAgentStylesheet(AppDesign.NORMAL);
        AppDesignStyleSupport.applyToScene(stage.getScene());
        stage.setWidth(1160);
        ((javafx.scene.layout.Region) stage.getScene().getRoot()).resize(1160, 700);
        layout(stage.getScene().getRoot());
        Button shell = toolbar.lookupAll(".toolbar-action").stream().filter(Button.class::isInstance)
            .map(Button.class::cast).filter(button -> I18n.get("toolbar.localShell").equals(button.getAccessibleText()))
            .findFirst().orElseThrow();
        stage.getScene().getRoot().requestFocus();
        layout(stage.getScene().getRoot());
        var detachments = new java.util.concurrent.atomic.AtomicInteger();
        shell.parentProperty().addListener((o, old, value) -> { if (value == null) detachments.incrementAndGet(); });
        int before = tabs.getTabs().size();
        injectWindowFocus(stage, false);
        mouseEvent(shell, javafx.scene.input.MouseEvent.MOUSE_PRESSED, true);
        injectWindowFocus(stage, true);
        layout(stage.getScene().getRoot());
        check(shell.getScene() == stage.getScene(), "Focus must preserve the clicked button");
        check(detachments.get() == 0, "Focusing a toolbar button must not detach it during the click: " + detachments.get());
        mouseEvent(shell, javafx.scene.input.MouseEvent.MOUSE_RELEASED, false);
        check(tabs.getTabs().size() == before + 1, "First click after focus change opens exactly one shell");
        List<Node> groups = toolbar.lookup(".toolbar-tasks") instanceof javafx.scene.layout.HBox row
            ? row.getChildren() : List.of();
        check(groups.size() == 3, "Three task groups");
        Node tools = groups.get(1);
        Node settings = groups.get(2);
        check(settings.getBoundsInParent().getMinX() - tools.getBoundsInParent().getMaxX() <= 12,
            "Settings immediately follow tools");
        check(tools.getBoundsInParent().getWidth() <= ((javafx.scene.layout.Region) tools).prefWidth(-1) + 1,
            "Tools do not stretch beyond the width required by their buttons");
        // Let the new terminal finish its deferred initialization before releasing it.
        Platform.runLater(() -> Platform.runLater(() -> {
            TerminalTab opened = (TerminalTab) tabs.getSelectionModel().getSelectedItem();
            opened.releaseResources();
            tabs.getTabs().remove(opened);
            second.hide();
            stage.hide();
            done.countDown();
        }));
    }

    private static void injectWindowFocus(Stage stage, boolean focused) {
        try {
            var method = javafx.stage.Window.class.getDeclaredMethod("setFocused", boolean.class);
            method.setAccessible(true);
            method.invoke(stage, focused);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inject the window focus event", error);
        }
    }

    /** Focus decoration must not resize controls or make ToolBarSkin reparent its items. */
    private static void checkStableButtonFocus(Stage stage, IconToolbar toolbar) {
        boolean wasFocused = stage.isFocused();
        injectWindowFocus(stage, true);
        Parent root = stage.getScene().getRoot();
        for (Node node : toolbar.lookupAll(".toolbar-action")) {
            Button button = (Button) node;
            if (button.isDisabled()) continue;
            root.requestFocus();
            layout(root);
            double width = button.getWidth();
            double height = button.getHeight();
            var detachments = new java.util.concurrent.atomic.AtomicInteger();
            javafx.beans.value.ChangeListener<Parent> listener = (observable, old, value) -> {
                if (value == null) detachments.incrementAndGet();
            };
            button.parentProperty().addListener(listener);
            try {
                button.requestFocus();
                layout(root);
                check(button.isFocused(), "Button accepts keyboard focus: " + button.getAccessibleText());
                check(button.getWidth() == width && button.getHeight() == height,
                    "Focus keeps button dimensions: " + button.getAccessibleText());
                check(detachments.get() == 0, "Focus keeps button attached: " + button.getAccessibleText());
            } finally {
                button.parentProperty().removeListener(listener);
            }
        }
        root.requestFocus();
        layout(root);
        injectWindowFocus(stage, wasFocused);
    }

    private static void mouseEvent(Button button, javafx.event.EventType<javafx.scene.input.MouseEvent> type,
                                   boolean pressed) {
        var center = button.localToScreen(button.getWidth() / 2, button.getHeight() / 2);
        button.fireEvent(new javafx.scene.input.MouseEvent(type, button.getWidth() / 2, button.getHeight() / 2,
            center.getX(), center.getY(), javafx.scene.input.MouseButton.PRIMARY, 1,
            false, false, false, false, pressed, false, false, false, false, true,
            new javafx.scene.input.PickResult(button, button.getWidth() / 2, button.getHeight() / 2)));
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> text.equals(button.getText())).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }

    private static void checkCatalogRows(Stage stage, IconToolbar toolbar) {
        var categories = (javafx.scene.control.ListView<?>) toolbar.lookup(".list-view");
        categories.getSelectionModel().select(1);
        Parent root = stage.getScene().getRoot();
        var scroll = (javafx.scene.control.ScrollPane) toolbar.lookup(".toolbar-catalog");
        var items = ((javafx.scene.layout.FlowPane) scroll.getContent()).getChildren();
        for (double fontSize : List.of(13.0, 20.0)) {
            items.forEach(item -> item.setStyle("-fx-font-size: " + fontSize + "px;"));
            for (double width : List.of(1160.0, 620.0)) {
                stage.setWidth(width);
                ((javafx.scene.layout.Region) root).resize(width, 700);
                for (int pass = 0; pass < 4; pass++) layout(root);
                for (Node item : items) {
                    Button button = (Button) item;
                    var text = new javafx.scene.text.Text(button.getText());
                    text.setFont(button.getFont());
                    check(text.getLayoutBounds().getWidth() <= button.getWidth()
                        - button.getInsets().getLeft() - button.getInsets().getRight(), "Catalog caption fits without truncation");
                    if ("SFTP-Manager".equals(button.getAccessibleText()) && fontSize == 20) {
                        check(List.of("SFTP-Manager", "SFTP-\nManager").contains(button.getText()),
                            "German Manager label keeps complete words: " + button.getText());
                    }
                }
                int row = 0;
                double previousLeft = -1;
                for (Node item : items) {
                    // FlowPane centers different-height buttons inside a row. Shadow bounds and
                    // distinct Y coordinates do not identify rows; a new row resets its X position.
                    var bounds = item.localToParent(item.getLayoutBounds());
                    if (bounds.getMinX() <= previousLeft) row++;
                    if (row < 2) {
                        check(bounds.getMaxY() <= scroll.getViewportBounds().getHeight() + 0.5,
                            "Two complete catalog rows fit the viewport at width " + width
                                + ": button bottom=" + bounds.getMaxY()
                                + ", viewport=" + scroll.getViewportBounds().getHeight()
                                + ", requested viewport=" + scroll.getPrefViewportHeight()
                                + ", height=" + item.getLayoutBounds().getHeight() + ", y=" + item.getLayoutY()
                                + ", preferred height=" + ((javafx.scene.layout.Region) item).prefHeight(-1));
                    }
                    previousLeft = bounds.getMinX();
                }
                check(row >= 1, "Settings catalog has at least two rows at width " + width);
            }
        }
        items.forEach(item -> item.setStyle(""));
        stage.setWidth(1160);
        ((javafx.scene.layout.Region) root).resize(1160, 700);
        layout(root);
        layout(root);
    }

    private static void layout(Parent node) {
        node.applyCss();
        requestLayoutTree(node);
        node.layout();
    }

    /** These synchronous checks run before native pulses can process resized child controls. */
    private static void requestLayoutTree(Parent node) {
        node.getChildrenUnmodifiable().stream().filter(Parent.class::isInstance).map(Parent.class::cast)
            .forEach(IconToolbarSmoke::requestLayoutTree);
        node.requestLayout();
    }

    private static void snapshot(Node node, Path path) throws Exception {
        ImageIO.write(SwingFXUtils.fromFXImage(node.snapshot(null, null), null), "png", path.toFile());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
