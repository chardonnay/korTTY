package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.telemetry.TelemetryService;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time opt-in prompt for existing installations that never saw the
 * first-run consent question (asked next to the master-password setup).
 * Any dismissal counts as "no" — the user is never asked again; the choice
 * stays one click away under Settings → Privacy.
 */
public final class TelemetryConsentDialog {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryConsentDialog.class);

    /** Shows the prompt once per installation; a no-op after any consent decision. */
    public static void maybeShow(KorTTYApplication app, Stage owner) {
        if (app == null) {
            return;
        }
        TelemetryService telemetryService = app.getTelemetryService();
        if (telemetryService == null || !telemetryService.isConsentPromptNeeded()) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.get("telemetry.consent.title"));
        alert.setHeaderText(I18n.get("telemetry.consent.header"));
        alert.setContentText(I18n.get("telemetry.consent.message"));
        ButtonType accept = new ButtonType(I18n.get("telemetry.consent.accept"), ButtonBar.ButtonData.OK_DONE);
        ButtonType learnMore = new ButtonType(I18n.get("telemetry.consent.learnMore"), ButtonBar.ButtonData.HELP);
        ButtonType decline = new ButtonType(I18n.get("telemetry.consent.decline"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(accept, learnMore, decline);
        DialogThemeHelper.applyTheme(alert);
        if (owner != null && owner.isShowing()) {
            alert.initOwner(owner);
        }

        // "More info" opens the guide chapter without closing the dialog.
        Node helpButton = alert.getDialogPane().lookupButton(learnMore);
        if (helpButton != null) {
            helpButton.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                try {
                    Window dialogWindow = alert.getDialogPane().getScene() != null
                        ? alert.getDialogPane().getScene().getWindow()
                        : owner;
                    GuideViewer.show(app, dialogWindow, TelemetryEvents.GUIDE_LOCATION);
                } catch (RuntimeException e) {
                    logger.warn("Could not open the guide chapter on anonymous data", e);
                }
            });
        }

        boolean granted = alert.showAndWait().orElse(decline) == accept;
        telemetryService.recordConsent(granted);
    }

    private TelemetryConsentDialog() {
    }
}
