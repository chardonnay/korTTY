package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.core.SessionJournalService;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.model.SplitPaneState;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Headed regression of terminal selection, shared capture and future splits, with local demo shells. */
public final class SessionJournalPaneScopeSmoke {
    private static TerminalTab tab;
    private static Stage stage;
    private static SessionJournalService service;
    private static Path home;

    private SessionJournalPaneScopeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        home = Files.createTempDirectory("kortty-journal-pane-smoke");
        System.setProperty("user.home", home.toString());
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        CountDownLatch connected = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                KorTTYApplication app = new KorTTYApplication();
                app.init();
                var settings = app.getGlobalSettingsManager().getSettings();
                settings.setLanguage("en");
                settings.setSessionJournalAiSummariesEnabled(false);
                settings.setSessionJournalStoragePath(home.resolve("journals").toString());
                LanguageManager.getInstance().initialize(settings);
                AppDesignStyleSupport.initializeGlobalStyling(settings.getAppDesign());
                service = app.getSessionJournalService();
                ServerConnection connection = new ServerConnection("Demo shell", "localhost", 22, "demo");
                connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
                connection.setLocalShellWorkingDirectory(home.toString());
                connection.getSessionJournalConfig().setAiSummariesEnabled(false);
                tab = new TerminalTab(connection, null);
                stage = new Stage();
                stage.setScene(new Scene(new TabPane(tab), 1000, 650));
                stage.show();
                tab.addOnFirstConnected(connected::countDown);
                tab.connect();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });

        try {
            check(connected.await(60, TimeUnit.SECONDS), "first local shell did not connect");
            CountDownLatch restored = new CountDownLatch(1);
            fx(() -> {
                tab.getTerminalView().restoreSplitLayout(
                    SplitPaneState.createSplit(Orientation.HORIZONTAL, 0.5, SplitPaneState.createLeaf(0),
                        SplitPaneState.createSplit(Orientation.VERTICAL, 0.5,
                            SplitPaneState.createLeaf(1), SplitPaneState.createLeaf(2))),
                    summary -> restored.countDown());
                return null;
            });
            check(restored.await(60, TimeUnit.SECONDS), "demo splits did not connect");
            var panes = fx(() -> tab.getTerminalView().getOrderedWidgets());
            check(panes.size() == 3, "expected three terminals");
            DialogPane dialog = openSelection();
            DialogPane selectedDialog = dialog;
            fx(() -> {
                VBox content = (VBox) selectedDialog.getContent();
                VBox choices = (VBox) ((ScrollPane) content.getChildren().get(1)).getContent();
                for (int i = 0; i < choices.getChildren().size(); i++) {
                    ((CheckBox) choices.getChildren().get(i)).setSelected(i != 1);
                }
                Path png = Path.of("build/smoke/journal-terminal-selection.png");
                Files.createDirectories(png.getParent());
                ImageIO.write(SwingFXUtils.fromFXImage(selectedDialog.snapshot(null, null), null), "png", png.toFile());
                ((Button) selectedDialog.lookupButton(ButtonType.APPLY)).fire();
                return null;
            });
            await(() -> fx(() -> tab.getTerminalView().getSessionJournalSession(panes.getFirst())) != null);
            var journal = fx(() -> tab.getTerminalView().getSessionJournalSession(panes.getFirst()));
            check(journal != null, "first terminal has no journal");
            check(fx(() -> tab.getTerminalView().getSessionJournalSession(panes.get(2))) == journal,
                "selected terminals have different journals");
            check(fx(() -> tab.getTerminalView().getSessionJournalSession(panes.get(1))) == null,
                "excluded terminal is recording");
            fx(() -> {
                tab.getTerminalView().focusWidget(panes.get(1));
                return null;
            });
            await(() -> fx(() -> tab.getTerminalView().getFocusedWidget() == panes.get(1)));
            fx(() -> {
                var hidden = TerminalTab.class.getDeclaredField("journalBarHiddenByUser");
                hidden.setAccessible(true);
                hidden.setBoolean(tab, true);
                tab.refreshJournalControlsVisibility();
                var bar = TerminalTab.class.getDeclaredField("journalBar");
                bar.setAccessible(true);
                check(((javafx.scene.Node) bar.get(tab)).isVisible(),
                    "focusing an excluded terminal hid the running journal bar");
                hidden.setBoolean(tab, false);
                tab.getTerminalView().focusWidget(panes.getFirst());
                return null;
            });
            Thread.sleep(500);
            for (int i = 0; i < panes.size(); i++) {
                int index = i;
                fx(() -> {
                    var connector = TerminalView.unwrapTerminalEffectConnector(panes.get(index).getTtyConnector());
                    connector.write("echo scope-marker-" + index + "\r");
                    return null;
                });
            }
            await(() -> service.readLogAfter(journal.getDirectory(), 0).stream()
                .anyMatch(entry -> entry.text().contains("scope-marker-2")));
            check(service.readLogAfter(journal.getDirectory(), 0).stream()
                .noneMatch(entry -> entry.text().contains("scope-marker-1")), "excluded output leaked");

            dialog = openSelection();
            DialogPane allDialog = dialog;
            fx(() -> {
                ((CheckBox) ((VBox) allDialog.getContent()).getChildren().getFirst()).setSelected(true);
                ((Button) allDialog.lookupButton(ButtonType.APPLY)).fire();
                return null;
            });
            await(() -> fx(() -> tab.getTerminalView().journalsAllPanes()));
            fx(() -> {
                var field = TerminalView.class.getDeclaredField("splitPane");
                field.setAccessible(true);
                ((TerminalSplitPane) field.get(tab.getTerminalView()))
                    .splitHorizontally(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL);
                return null;
            });
            await(() -> fx(() -> tab.getTerminalView().getOrderedWidgets().size()) == 4);
            var allPanes = fx(() -> tab.getTerminalView().getOrderedWidgets());
            for (SithTermFxWidget pane : allPanes) {
                check(fx(() -> tab.getTerminalView().getSessionJournalSession(pane)) == journal,
                    "all-terminals selection did not share the existing journal");
            }
            check(service.getLiveSessions().size() == 1, "multiple journal sessions were created");
            fx(() -> {
                tab.getTerminalView().stopPaneJournal(allPanes.getFirst());
                return null;
            });
            check(journal.isActive(), "removing one terminal stopped the shared journal");
            fx(() -> {
                for (SithTermFxWidget pane : allPanes) {
                    tab.getTerminalView().stopPaneJournal(pane);
                }
                return null;
            });
            await(() -> !journal.isActive());
            var storedSettings = new de.kortty.model.GlobalSettings();
            storedSettings.setSessionJournalStoragePath(home.resolve("journals").toString());
            check(service.listJournals(storedSettings).size() == 1, "expected one persisted journal");
            System.out.println("SessionJournalPaneScopeSmoke OK");
        } finally {
            fx(() -> {
                if (tab != null) {
                    tab.releaseResources();
                }
                if (stage != null) {
                    stage.close();
                }
                return null;
            });
            Platform.exit();
        }
        System.exit(0);
    }

    private static DialogPane openSelection() throws Exception {
        Platform.runLater(tab::toggleJournalFromMenuOrShortcut);
        await(() -> fx(SessionJournalPaneScopeSmoke::selectionDialog) != null);
        return fx(SessionJournalPaneScopeSmoke::selectionDialog);
    }

    private static DialogPane selectionDialog() {
        return Window.getWindows().stream().filter(Window::isShowing)
            .map(window -> window.getScene().getRoot())
            .filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
            .filter(pane -> I18n.get("terminal.journal.scope.header").equals(pane.getHeaderText()))
            .findFirst().orElse(null);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.call() && System.nanoTime() < end) {
            Thread.sleep(100);
        }
        check(condition.call(), "timed out waiting for journal state");
    }

    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
