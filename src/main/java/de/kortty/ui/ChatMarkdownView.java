package de.kortty.ui;

import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.shape.Path;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

import java.util.List;

/** Native, wrapping Markdown preview. Model HTML is text, never executable content. */
final class ChatMarkdownView extends TextFlow {
    private static final Parser PARSER = Parser.builder()
        .extensions(List.of(StrikethroughExtension.create())).build();
    private final StringBuilder displayedText = new StringBuilder();
    private final Path selection = new Path();
    private int anchor;
    private int caret;

    static ChatMarkdownView markdown(String source, int fontSize) {
        ChatMarkdownView view = new ChatMarkdownView();
        view.appendChildren(PARSER.parse(source != null ? source : ""), new Style(fontSize, false, false, false, false), 0);
        view.finish();
        return view;
    }

    static ChatMarkdownView plainText(String source, int fontSize) {
        ChatMarkdownView view = new ChatMarkdownView();
        view.append(source != null ? source : "", new Style(fontSize, false, false, false, false));
        view.finish();
        return view;
    }

    private ChatMarkdownView() {
        getStyleClass().add("ai-chat-markdown");
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        setLineSpacing(3);
        setCursor(Cursor.TEXT);
        setFocusTraversable(true);
        selection.setManaged(false);
        selection.setMouseTransparent(true);
        selection.getStyleClass().add("ai-chat-text-selection");
        getChildren().add(selection);
        setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                requestFocus();
                caret = indexAt(event.getX(), event.getY());
                if (!event.isShiftDown()) {
                    anchor = caret;
                }
                updateSelection();
            }
        });
        setOnMouseDragged(event -> {
            if (event.isPrimaryButtonDown()) {
                caret = indexAt(event.getX(), event.getY());
                updateSelection();
            }
        });
        setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
                copySelection();
                event.consume();
            } else if (event.isShortcutDown() && event.getCode() == KeyCode.A) {
                anchor = 0;
                caret = displayedText.length();
                updateSelection();
                event.consume();
            }
        });
        MenuItem copy = new MenuItem(I18n.get("menu.edit.copy"));
        copy.setOnAction(event -> copySelection());
        ContextMenu menu = new ContextMenu(copy);
        setOnContextMenuRequested(event -> {
            copy.setDisable(anchor == caret);
            menu.show(this, event.getScreenX(), event.getScreenY());
        });
    }

    private void finish() {
        setAccessibleText(displayedText.toString());
    }

    String displayedText() {
        return displayedText.toString();
    }

    private int indexAt(double x, double y) {
        return Math.max(0, Math.min(displayedText.length(), hitTest(new Point2D(x, y)).getInsertionIndex()));
    }

    private void copySelection() {
        if (anchor != caret) {
            de.kortty.core.KorttyClipboard.setText(displayedText.substring(Math.min(anchor, caret), Math.max(anchor, caret)));
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        updateSelection();
    }

    private void updateSelection() {
        selection.getElements().setAll(rangeShape(Math.min(anchor, caret), Math.max(anchor, caret)));
    }

    private void appendChildren(org.commonmark.node.Node parent, Style style, int depth) {
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            appendNode(child, style, depth);
        }
    }

    private void appendNode(org.commonmark.node.Node node, Style style, int depth) {
        if (node instanceof org.commonmark.node.Text text) {
            append(text.getLiteral(), style);
        } else if (node instanceof Code code) {
            append(code.getLiteral(), style.codeStyle());
        } else if (node instanceof StrongEmphasis) {
            appendChildren(node, style.boldStyle(), depth);
        } else if (node instanceof Emphasis) {
            appendChildren(node, new Style(style.size, style.bold, true, style.code, style.strike), depth);
        } else if (node instanceof Strikethrough) {
            appendChildren(node, new Style(style.size, style.bold, style.italic, style.code, true), depth);
        } else if (node instanceof Heading heading) {
            separate(2, style);
            appendChildren(node, new Style(style.size * (1.0 + (7 - heading.getLevel()) * 0.12), true, false, false, false), depth);
            separate(2, style);
        } else if (node instanceof Paragraph) {
            appendChildren(node, style, depth);
            if (node.getNext() != null) {
                separate(node.getParent() instanceof ListItem ? 1 : 2, style);
            }
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            separate(depth == 0 ? 2 : 1, style);
            int number = node instanceof OrderedList ordered ? ordered.getMarkerStartNumber() : 0;
            for (org.commonmark.node.Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                append("    ".repeat(depth) + (node instanceof OrderedList ? number++ + ". " : "• "), style);
                appendChildren(item, style, depth + 1);
                if (item.getNext() != null) {
                    separate(1, style);
                }
            }
            if (node.getNext() != null) {
                separate(depth == 0 ? 2 : 1, style);
            }
        } else if (node instanceof BlockQuote) {
            separate(2, style);
            append("▎ ", style.boldStyle());
            appendChildren(node, new Style(style.size, style.bold, true, style.code, style.strike), depth);
            if (node.getNext() != null) {
                separate(2, style);
            }
        } else if (node instanceof SoftLineBreak) {
            append(" ", style);
        } else if (node instanceof HardLineBreak) {
            append("\n", style);
        } else if (node instanceof ThematicBreak) {
            separate(2, style);
            append("────────────────────", style);
            separate(2, style);
        } else if (node instanceof FencedCodeBlock code) {
            append(code.getLiteral(), style.codeStyle());
        } else if (node instanceof IndentedCodeBlock code) {
            append(code.getLiteral(), style.codeStyle());
        } else if (node instanceof HtmlInline html) {
            append(html.getLiteral(), style);
        } else if (node instanceof HtmlBlock html) {
            append(html.getLiteral(), style);
        } else if (node instanceof Link link) {
            appendChildren(node, style, depth);
            append(" (" + link.getDestination() + ")", style);
        } else {
            // Images retain their alt text; loading model-supplied URLs is deliberately opt-in elsewhere.
            appendChildren(node, style, depth);
        }
    }

    private void separate(int lines, Style style) {
        if (displayedText.isEmpty()) {
            return;
        }
        int existing = 0;
        for (int i = displayedText.length() - 1; i >= 0 && displayedText.charAt(i) == '\n'; i--) {
            existing++;
        }
        if (existing < lines) {
            append("\n".repeat(lines - existing), style);
        }
    }

    private void append(String value, Style style) {
        Text text = new Text(value);
        text.setFont(Font.font(style.code ? "Monospaced" : Font.getDefault().getFamily(),
            style.bold ? FontWeight.BOLD : FontWeight.NORMAL,
            style.italic ? FontPosture.ITALIC : FontPosture.REGULAR, style.size));
        text.setStrikethrough(style.strike);
        text.getStyleClass().add(style.code ? "ai-chat-inline-code" : "ai-chat-prose");
        getChildren().add(text);
        displayedText.append(value);
    }

    private record Style(double size, boolean bold, boolean italic, boolean code, boolean strike) {
        Style boldStyle() { return new Style(size, true, italic, code, strike); }
        Style codeStyle() { return new Style(size, bold, italic, true, strike); }
    }
}
