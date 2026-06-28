package de.kortty.ui;

import de.kortty.core.AiChatContentSupport;
import de.kortty.core.AiMarkdownTableSupport;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.List;

/**
 * Renders assistant/user chat content (markdown text, tables, code) into JavaFX nodes. Shared by the
 * swarm chat ({@link SwarmAgentTab}); reuses the same {@code AiChatContentSupport} /
 * {@code AiMarkdownTableSupport} splitters as {@link AiResultTab} so rendering stays consistent.
 */
public final class AiChatRenderSupport {

    private AiChatRenderSupport() {
    }

    /** Appends rendered content nodes for one message into {@code parent}. */
    public static void renderInto(VBox parent, boolean assistant, String content, int fontSize) {
        if (parent == null || content == null || content.isBlank()) {
            return;
        }
        if (!assistant) {
            parent.getChildren().add(selectableText(content, fontSize));
            return;
        }
        for (AiChatContentSupport.ContentSection section : AiChatContentSupport.splitContent(content)) {
            if (section.code()) {
                parent.getChildren().add(codeBlock(section.content(), fontSize));
            } else if (!section.content().isBlank()) {
                for (AiChatContentSupport.StructuredTextBlock block
                    : AiChatContentSupport.splitStructuredText(section.content())) {
                    if (block.type() == AiChatContentSupport.StructuredTextBlock.Type.TABLE) {
                        parent.getChildren().add(markdownTable(block.tableRows(), fontSize));
                    } else if (block.text() != null && !block.text().isBlank()) {
                        parent.getChildren().add(selectableText(block.text(), fontSize));
                    }
                }
            }
        }
    }

    private static Node selectableText(String text, int fontSize) {
        Label label = new Label(text != null ? text.trim() : "");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setFont(Font.font(fontSize));
        return label;
    }

    private static Node codeBlock(String code, int fontSize) {
        TextArea area = new TextArea(code != null ? code : "");
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle(String.format("-fx-font-family: 'monospace'; -fx-font-size: %dpx;", fontSize));
        int lines = Math.max(2, (code != null ? code : "").split("\\R", -1).length);
        area.setPrefRowCount(Math.min(16, lines));
        return area;
    }

    private static Node markdownTable(List<List<String>> rows, int fontSize) {
        AiMarkdownTableSupport.RenderedMarkdownTable table = AiMarkdownTableSupport.buildRenderedTable(rows);
        GridPane grid = new GridPane();
        grid.setHgap(0);
        grid.setVgap(0);
        grid.setStyle("-fx-background-color: rgba(18,24,32,0.28); -fx-background-radius: 8;");
        int columns = table.header().size();
        for (int column = 0; column < columns; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            constraints.setPercentWidth(100.0 / Math.max(1, columns));
            grid.getColumnConstraints().add(constraints);
        }
        for (int column = 0; column < columns; column++) {
            grid.add(cell(table.header().get(column), true, fontSize), column, 0);
        }
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            List<String> row = table.rows().get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                grid.add(cell(row.get(column), false, fontSize), column, rowIndex + 1);
            }
        }
        return grid;
    }

    private static Node cell(String value, boolean header, int fontSize) {
        Label label = new Label(value != null ? value : "");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPadding(new Insets(6, 10, 6, 10));
        label.setFont(Font.font(fontSize));
        label.setStyle(header
            ? "-fx-font-weight: bold; -fx-background-color: rgba(52,120,246,0.25);"
                + " -fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 0 1 1 0;"
            : "-fx-background-color: transparent;"
                + " -fx-border-color: rgba(255,255,255,0.08); -fx-border-width: 0 1 1 0;");
        return label;
    }
}
