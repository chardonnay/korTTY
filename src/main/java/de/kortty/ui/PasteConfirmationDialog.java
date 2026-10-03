package de.kortty.ui;

import de.kortty.core.KorttyClipboard;
import de.kortty.core.LanguageManager;
import de.kortty.paste.PasteConfirmationRequest;
import de.kortty.paste.PasteConfirmer;
import de.kortty.paste.PasteInspection;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Asks whether a terminal paste may reach its pane, and shows why it asks: the reasons, whether the
 * program uses bracketed paste, a preview in which control and invisible characters are visible,
 * and where the text comes from.
 *
 * <ul>
 *   <li>Opened with {@code show()}, never {@code showAndWait()}: the paste starts inside a key or
 *       mouse handler of the terminal, and a nested event loop there would run in the middle of
 *       it. The answer arrives when the dialog closes.</li>
 *   <li>Type-ahead never confirms a paste. Cancel is the default button and has the focus, so
 *       Enter, Space and Escape all drop the paste; Paste has to be clicked, or reached with Tab and
 *       pressed with Space. This is the precedent of {@code TerminalView.confirmTunnelSet}.</li>
 *   <li>The preview's own right-click menu is replaced by a single Copy that goes through
 *       {@link KorttyClipboard}, so in the enterprise policy's internal clipboard mode the text
 *       cannot reach the system clipboard that way.</li>
 *   <li>Tab and Shift+Tab leave the preview for the Cancel button instead of being swallowed by the
 *       text area.</li>
 * </ul>
 */
public final class PasteConfirmationDialog implements PasteConfirmer {

    /** Marks the dialog pane, so a smoke test can find the dialog among the open windows. */
    public static final String STYLE_CLASS = "paste-confirmation-dialog";

    private static final double WIDTH = 640;

    private static final int MAX_PREVIEW_ROWS = 12;

    private static final int MIN_PREVIEW_ROWS = 3;

    private final Supplier<Window> owner;

    /** @param owner the window the dialog belongs to, read when it opens; may return null */
    public PasteConfirmationDialog(Supplier<Window> owner) {
        this.owner = owner != null ? owner : () -> null;
    }

    @Override
    public void confirm(PasteConfirmationRequest request, Consumer<Boolean> answer) {
        build(request, answer).show();
    }

    /**
     * The dialog for {@code request}, ready to show.
     *
     * @param answer receives whether to paste when the dialog closes
     */
    Alert build(PasteConfirmationRequest request, Consumer<Boolean> answer) {
        PasteConfirmationContent content = PasteConfirmationContent.of(request, PasteInspection.of(request.text()),
            I18n::get, currentLocale());

        ButtonType cancel = new ButtonType(content.cancelButton(), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType paste = new ButtonType(content.pasteButton(), ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", cancel, paste);
        alert.getDialogPane().getStyleClass().add(STYLE_CLASS);
        DialogThemeHelper.applyTheme(alert);
        Window ownerWindow = owner.get();
        if (ownerWindow != null) {
            alert.initOwner(ownerWindow);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.setTitle(content.title());
        alert.setHeaderText(content.header());

        Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancel);
        Button pasteButton = (Button) alert.getDialogPane().lookupButton(paste);
        pasteButton.setDefaultButton(false);
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);

        alert.getDialogPane().setContent(body(content, cancelButton));
        alert.getDialogPane().setPrefWidth(UiFontScaleSupport.scaleDimension(WIDTH, true));
        alert.setResizable(true);
        alert.setOnShown(event -> cancelButton.requestFocus());
        alert.setOnHidden(event -> answer.accept(paste.equals(alert.getResult())));
        return alert;
    }

    private static VBox body(PasteConfirmationContent content, Button cancelButton) {
        double textWidth = UiFontScaleSupport.scaleDimension(WIDTH - 40, true);
        VBox body = new VBox(8);

        Label summary = wrapped(content.summary(), textWidth);
        summary.setStyle("-fx-font-weight: bold;");
        body.getChildren().add(summary);
        for (String reason : content.reasons()) {
            body.getChildren().add(wrapped("\u2022 " + reason, textWidth));
        }
        for (String note : content.notes()) {
            body.getChildren().add(hint(note, textWidth));
        }

        Label previewLabel = wrapped(content.previewLabel(), textWidth);
        TextArea preview = previewArea(content, cancelButton);
        previewLabel.setLabelFor(preview);
        body.getChildren().addAll(previewLabel, preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        if (content.previewLegend() != null) {
            body.getChildren().add(hint(content.previewLegend(), textWidth));
        }
        if (content.previewTruncated() != null) {
            body.getChildren().add(hint(content.previewTruncated(), textWidth));
        }
        body.getChildren().add(hint(content.settingsHint(), textWidth));
        return body;
    }

    private static TextArea previewArea(PasteConfirmationContent content, Button cancelButton) {
        TextArea preview = new TextArea(content.previewText());
        preview.setEditable(false);
        preview.setWrapText(false);
        // One row more than the preview has, so a short preview needs no scroll bar.
        long rows = content.previewText().chars().filter(c -> c == '\n').count() + 2;
        preview.setPrefRowCount((int) Math.max(MIN_PREVIEW_ROWS, Math.min(MAX_PREVIEW_ROWS, rows)));
        preview.setStyle("-fx-font-family: 'Monospaced';");
        preview.setAccessibleText(content.accessibleText());

        // Replacing the menu removes the text area's built-in Copy, which would always write to the
        // system clipboard; this one follows the clipboard policy.
        MenuItem copy = new MenuItem(I18n.get("terminal.contextMenu.copy"));
        copy.disableProperty().bind(preview.selectedTextProperty().isEmpty());
        copy.setOnAction(event -> KorttyClipboard.copySelection(preview));
        preview.setContextMenu(new ContextMenu(copy));

        preview.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB && !event.isControlDown() && !event.isAltDown()
                    && !event.isMetaDown()) {
                event.consume();
                cancelButton.requestFocus();
            }
        });
        return preview;
    }

    private static Label wrapped(String text, double width) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(width);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Node hint(String text, double width) {
        Label label = wrapped(text, width);
        label.setStyle(MutedTextStyle.MUTED);
        return label;
    }

    private static Locale currentLocale() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            return locale != null ? locale : Locale.getDefault();
        } catch (RuntimeException e) {
            return Locale.getDefault();
        }
    }
}
