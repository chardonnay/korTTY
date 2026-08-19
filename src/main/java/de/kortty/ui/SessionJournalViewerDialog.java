package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.WindowGeometry;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Dialog shell around {@link SessionJournalViewerPane}: window chrome, geometry persistence and
 * the close lifecycle. All viewer behavior — page, bridge, edit mode, export — lives in the pane,
 * which the docked live panel embeds in compact form.
 */
public class SessionJournalViewerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalViewerDialog.class);

    private final MainWindow ownerWindow;
    private final KorTTYApplication app;
    private final Path journalDir;
    private final SessionJournalViewerPane pane;

    public SessionJournalViewerDialog(MainWindow ownerWindow, SessionJournalMeta meta) {
        this.ownerWindow = ownerWindow;
        this.app = KorTTYApplication.getInstance();
        this.journalDir = meta.getDirectory();
        initModality(Modality.NONE);
        setTitle(I18n.get("journal.manager.title") + " — "
            + (meta.getTitle() != null ? meta.getTitle() : ""));
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        pane = new SessionJournalViewerPane(ownerWindow, journalDir,
            SessionJournalViewerPane.Mode.FULL,
            newTitle -> setTitle(I18n.get("journal.manager.title") + " — " + newTitle));
        getDialogPane().setContent(pane);
        getDialogPane().setPrefSize(1000, 680);
        getDialogPane().setMinSize(680, 460);
        restoreGeometry();

        // WebKit engines leak native memory unless the page is explicitly unloaded; the handler
        // must stay idempotent (DIALOG_HIDDEN fires twice when hosted in a tab).
        setOnCloseRequest(event -> saveGeometry());
        setOnHidden(event -> {
            saveGeometry();
            pane.dispose();
            ownerWindow.onSessionJournalViewerClosed(journalDir);
        });
    }

    /**
     * Reopens the journal window where and how big the user last left it. Hosted in a tool tab
     * there is no window of our own, so nothing is restored or stored.
     */
    private void restoreGeometry() {
        GlobalSettings settings = settings();
        if (settings != null) {
            DialogGeometrySupport.restore(this, settings.getSessionJournalViewerGeometry());
        }
    }

    private void saveGeometry() {
        if (isHostedInTab()) {
            return; // the pane's window is the main window's stage, not this dialog's geometry
        }
        WindowGeometry geometry = DialogGeometrySupport.capture(this);
        var settingsManager = app != null ? app.getGlobalSettingsManager() : null;
        if (geometry == null || settingsManager == null || settingsManager.getSettings() == null) {
            return;
        }
        settingsManager.getSettings().setSessionJournalViewerGeometry(geometry);
        try {
            settingsManager.save();
        } catch (Exception e) {
            logger.warn("Could not save the journal viewer geometry: {}", e.getMessage());
        }
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
    }

    /** The embedded viewer pane, for navigation targets (jump to entry/log seq) after opening. */
    SessionJournalViewerPane getPane() {
        return pane;
    }
}
