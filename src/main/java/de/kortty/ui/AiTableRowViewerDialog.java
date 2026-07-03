package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Readable single-row view for AI markdown tables: clicking a table row in a chat opens this
 * non-modal window, which lays the row out as "column header + full value" sections instead of a
 * cramped table cell. Offers font size +/- and copy-to-clipboard. {@code <br>} markers that
 * aggregation/LLM answers use for in-cell line breaks are decoded into real newlines.
 */
final class AiTableRowViewerDialog {

    private static final Pattern BR_PATTERN = Pattern.compile("(?i)<br\\s*/?>");
    private static final int MIN_FONT_SIZE = 9;
    private static final int MAX_FONT_SIZE = 32;
    private static final int DEFAULT_FONT_SIZE = 14;

    private AiTableRowViewerDialog() {
    }

    static void open(Window owner, List<String> headers, List<String> values) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initModality(Modality.NONE);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setResizable(true);
        String firstValue = values != null && !values.isEmpty() && values.get(0) != null
            ? decodeCellText(values.get(0)).strip()
            : "";
        dialog.setTitle(!firstValue.isBlank() ? firstValue : I18n.get("ai.table.rowView.title"));

        List<Label> headerLabels = new ArrayList<>();
        List<Label> valueLabels = new ArrayList<>();
        VBox sections = new VBox(14);
        sections.setPadding(new Insets(12));
        int count = headers != null ? Math.max(headers.size(), values != null ? values.size() : 0) : 0;
        for (int i = 0; i < count; i++) {
            String header = headers != null && i < headers.size() && headers.get(i) != null
                ? decodeCellText(headers.get(i))
                : "";
            String value = values != null && i < values.size() && values.get(i) != null
                ? decodeCellText(values.get(i))
                : "";
            Label headerLabel = new Label(header);
            headerLabel.setWrapText(true);
            headerLabels.add(headerLabel);
            Label valueLabel = new Label(value.isBlank() ? "—" : value);
            valueLabel.setWrapText(true);
            valueLabel.setMaxWidth(Double.MAX_VALUE);
            valueLabels.add(valueLabel);
            VBox section = new VBox(4, headerLabel, valueLabel);
            section.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 8;"
                + " -fx-padding: 10;");
            sections.getChildren().add(section);
        }
        applyFontSize(headerLabels, valueLabels, DEFAULT_FONT_SIZE);

        ScrollPane scrollPane = new ScrollPane(sections);
        scrollPane.setFitToWidth(true);

        int[] fontSize = {DEFAULT_FONT_SIZE};
        Label status = new Label();
        status.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        Button copyButton = new Button(I18n.get("ai.result.copy"));
        copyButton.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(buildClipboardText(headers, values));
            Clipboard.getSystemClipboard().setContent(content);
            status.setText(I18n.get("ai.workflow.copied"));
        });
        Button smallerButton = new Button("A−");
        smallerButton.setTooltip(new Tooltip(I18n.get("ai.table.rowView.fontSmaller")));
        smallerButton.setOnAction(e -> {
            fontSize[0] = Math.max(MIN_FONT_SIZE, fontSize[0] - 1);
            applyFontSize(headerLabels, valueLabels, fontSize[0]);
        });
        Button biggerButton = new Button("A+");
        biggerButton.setTooltip(new Tooltip(I18n.get("ai.table.rowView.fontBigger")));
        biggerButton.setOnAction(e -> {
            fontSize[0] = Math.min(MAX_FONT_SIZE, fontSize[0] + 1);
            applyFontSize(headerLabels, valueLabels, fontSize[0]);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, copyButton, smallerButton, biggerButton, status, spacer);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, toolbar, scrollPane);
        content.setPadding(new Insets(10));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(680, 560);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        DialogThemeHelper.applyTheme(dialog.getDialogPane());
        dialog.show();
    }

    private static void applyFontSize(List<Label> headerLabels, List<Label> valueLabels, int size) {
        for (Label header : headerLabels) {
            header.setFont(Font.font(null, FontWeight.BOLD, size));
        }
        for (Label value : valueLabels) {
            value.setFont(Font.font(size));
        }
    }

    /** Converts {@code <br>} variants (any case, with or without slash) into real newlines. */
    static String decodeCellText(String value) {
        if (value == null) {
            return "";
        }
        return BR_PATTERN.matcher(value).replaceAll("\n");
    }

    /** Plain-text form of the row for the clipboard: "Header:\nvalue" sections. */
    static String buildClipboardText(List<String> headers, List<String> values) {
        StringBuilder sb = new StringBuilder();
        int count = headers != null ? Math.max(headers.size(), values != null ? values.size() : 0) : 0;
        for (int i = 0; i < count; i++) {
            String header = headers != null && i < headers.size() && headers.get(i) != null
                ? decodeCellText(headers.get(i)).strip()
                : "";
            String value = values != null && i < values.size() && values.get(i) != null
                ? decodeCellText(values.get(i)).strip()
                : "";
            if (header.isBlank() && value.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(String.format(Locale.ROOT, "%n%n"));
            }
            if (!header.isBlank()) {
                sb.append(header).append(':').append(String.format(Locale.ROOT, "%n"));
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
