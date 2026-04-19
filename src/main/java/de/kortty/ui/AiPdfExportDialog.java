package de.kortty.ui;

import de.kortty.core.AiChatExportContext;
import de.kortty.core.AiPdfExportOptions;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Dialog for per-export PDF options of AI chat exports.
 */
public class AiPdfExportDialog extends ThemeAwareDialog<AiPdfExportOptions> {

    private final RadioButton reportModeRadio;
    private final RadioButton compactModeRadio;
    private final CheckBox includeMetadataCheck;
    private final TextField documentTitleField;
    private final TextField documentProducerField;
    private final TextField documentSubjectField;
    private final CheckBox includeBookmarksCheck;

    public AiPdfExportDialog(Window owner, AiChatExportContext exportContext, AiPdfExportOptions defaults) {
        AiPdfExportOptions localDefaults = defaults != null
            ? defaults
            : AiPdfExportOptions.defaults(exportContext != null ? exportContext.title() : null);

        setTitle(I18n.get("ai.result.export.pdf.options.title"));
        setHeaderText(I18n.get("ai.result.export.pdf.options.header"));
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.WINDOW_MODAL);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ToggleGroup layoutToggleGroup = new ToggleGroup();
        reportModeRadio = new RadioButton(I18n.get("ai.result.export.pdf.layout.report"));
        reportModeRadio.setToggleGroup(layoutToggleGroup);
        compactModeRadio = new RadioButton(I18n.get("ai.result.export.pdf.layout.compact"));
        compactModeRadio.setToggleGroup(layoutToggleGroup);
        if (localDefaults.layoutMode() == AiPdfExportOptions.LayoutMode.COMPACT) {
            compactModeRadio.setSelected(true);
        } else {
            reportModeRadio.setSelected(true);
        }

        includeMetadataCheck = new CheckBox(I18n.get("ai.result.export.pdf.metadata"));
        includeMetadataCheck.setSelected(localDefaults.includeDocumentMetadata());

        documentTitleField = new TextField(valueOrDefault(localDefaults.documentTitle(), exportContext.title()));
        documentProducerField = new TextField(valueOrDefault(localDefaults.documentProducer(), "KorTTY by Daniel Mengel"));
        documentSubjectField = new TextField(valueOrDefault(localDefaults.documentSubject(), "AI chat export"));

        GridPane metadataGrid = new GridPane();
        metadataGrid.setHgap(10);
        metadataGrid.setVgap(10);
        metadataGrid.setPadding(new Insets(6, 0, 0, 24));
        metadataGrid.add(new Label(I18n.get("ai.result.export.pdf.metadata.title")), 0, 0);
        metadataGrid.add(documentTitleField, 1, 0);
        metadataGrid.add(new Label(I18n.get("ai.result.export.pdf.metadata.producer")), 0, 1);
        metadataGrid.add(documentProducerField, 1, 1);
        metadataGrid.add(new Label(I18n.get("ai.result.export.pdf.metadata.subject")), 0, 2);
        metadataGrid.add(documentSubjectField, 1, 2);
        GridPane.setHgrow(documentTitleField, Priority.ALWAYS);
        GridPane.setHgrow(documentProducerField, Priority.ALWAYS);
        GridPane.setHgrow(documentSubjectField, Priority.ALWAYS);

        includeBookmarksCheck = new CheckBox(I18n.get("ai.result.export.pdf.bookmarks"));
        includeBookmarksCheck.setSelected(localDefaults.includeBookmarks());

        includeMetadataCheck.selectedProperty().addListener((obs, oldVal, newVal) -> setMetadataControlsDisabled(!newVal));
        setMetadataControlsDisabled(!includeMetadataCheck.isSelected());

        VBox content = new VBox(
            12,
            new Label(I18n.get("ai.result.export.pdf.layout")),
            reportModeRadio,
            compactModeRadio,
            includeMetadataCheck,
            metadataGrid,
            includeBookmarksCheck);
        content.setPadding(new Insets(6, 0, 0, 0));
        getDialogPane().setContent(content);

        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        if (okButton != null) {
            okButton.setDefaultButton(true);
        }

        setResultConverter(buttonType -> buttonType == ButtonType.OK ? buildResult() : null);
    }

    private void setMetadataControlsDisabled(boolean disabled) {
        documentTitleField.setDisable(disabled);
        documentProducerField.setDisable(disabled);
        documentSubjectField.setDisable(disabled);
    }

    private AiPdfExportOptions buildResult() {
        return new AiPdfExportOptions(
            compactModeRadio.isSelected() ? AiPdfExportOptions.LayoutMode.COMPACT : AiPdfExportOptions.LayoutMode.REPORT,
            includeMetadataCheck.isSelected(),
            documentTitleField.getText(),
            documentProducerField.getText(),
            documentSubjectField.getText(),
            includeBookmarksCheck.isSelected());
    }

    private String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
