package de.kortty.ui;

import de.kortty.core.AiSentContext;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlock.SentPiece;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Mitgesendet" disclosure under a chat answer or in an agent run: one collapsed line with the counts and the tokens
 * that were sent, expanded to the memory parts, the skills and the external files as the provider received them.
 *
 * <p>The texts are the masked copies the request carried. A memory row shows the first line of its text and its tokens;
 * its tooltip shows the text itself.</p>
 */
final class SentContextView {

    /** Where the disclosure sits: under a chat answer, or in an agent run's activity list or run tab. */
    enum Style {
        CHAT, AGENT
    }

    private static final int TOOLTIP_CHARS = 1500;
    private static final String SEPARATOR = " · ";
    private static final String COLLAPSED = "▸ ";
    private static final String EXPANDED = "▾ ";

    private SentContextView() {
    }

    /**
     * The collapsed disclosure for {@code context}. A font size of zero or less keeps the font of the scene.
     */
    static VBox create(AiSentContext context, Style style, double fontSize) {
        String summary = summary(context);
        String rowStyle = style == Style.CHAT ? "ai-chat-text" : "ai-agent-detail";
        VBox body = new VBox(4);
        if (style == Style.CHAT) {
            body.getStyleClass().add("ai-chat-reasoning");
        }
        fillBody(body, context, style, rowStyle, fontSize);
        body.setVisible(false);
        body.setManaged(false);

        Button toggle = new Button(COLLAPSED + summary);
        toggle.getStyleClass().add(style == Style.CHAT ? "ai-chat-reasoning-toggle" : "ai-agent-toggle-button");
        toggle.setFocusTraversable(false);
        toggle.setTooltip(new Tooltip(I18n.get("ai.memory.sent.tooltip")));
        if (fontSize > 0) {
            toggle.setFont(Font.font(fontSize));
        }
        toggle.setOnAction(event -> {
            boolean show = !body.isVisible();
            body.setVisible(show);
            body.setManaged(show);
            toggle.setText((show ? EXPANDED : COLLAPSED) + summary);
        });
        return new VBox(4, toggle, body);
    }

    /** "Mitgesendet: Memory: 3 · Skills: 2 · 812 Tokens", with the parts that are not empty. */
    private static String summary(AiSentContext context) {
        List<String> parts = new ArrayList<>();
        if (!context.entries().isEmpty()) {
            parts.add(I18n.get("ai.memory.sent.count.memory", context.entries().size()));
        }
        if (!context.skills().isEmpty()) {
            parts.add(I18n.get("ai.memory.sent.count.skills", context.skills().size()));
        }
        if (!context.files().isEmpty()) {
            parts.add(I18n.get("ai.memory.sent.count.files", context.files().size()));
        }
        parts.add(I18n.get("ai.memory.sent.tokens", context.tokens()));
        return I18n.get("ai.memory.sent.toggle", String.join(SEPARATOR, parts));
    }

    private static void fillBody(VBox body, AiSentContext context, Style style, String rowStyle, double fontSize) {
        if (!context.entries().isEmpty()) {
            body.getChildren().add(row(I18n.get("ai.memory.sent.count.memory", context.entries().size()), null,
                rowStyle, fontSize, true));
            for (SentPiece piece : context.entries()) {
                body.getChildren().add(row(originText(piece.origin()) + SEPARATOR
                    + AiSentContext.firstLine(piece.text()) + SEPARATOR + tokens(piece.tokens()),
                    piece.text(), rowStyle, fontSize, false));
            }
        }
        if (context.omittedCount() > 0) {
            body.getChildren().add(row(I18n.get("ai.memory.sent.omitted", context.omittedCount()), null, rowStyle,
                fontSize, false));
        }
        if (!context.skills().isEmpty()) {
            body.getChildren().add(row(I18n.get("ai.memory.sent.count.skills", context.skills().size()), null,
                rowStyle, fontSize, true));
            for (AiSentContext.Skill skill : context.skills()) {
                List<String> parts = new ArrayList<>();
                parts.add(skill.name());
                parts.add(I18n.get(skill.automatic() ? "ai.memory.sent.skill.automatic" : "ai.memory.sent.skill.pinned"));
                parts.add(tokens(skill.tokens()));
                if (style == Style.AGENT) {
                    parts.add(I18n.get("ai.memory.sent.turns", skill.turns()));
                }
                body.getChildren().add(row(String.join(SEPARATOR, parts), skill.learnedRules(), rowStyle, fontSize,
                    false));
            }
        }
        if (!context.files().isEmpty()) {
            body.getChildren().add(row(I18n.get("ai.memory.sent.count.files", context.files().size()), null,
                rowStyle, fontSize, true));
            for (SentPiece file : context.files()) {
                List<String> parts = new ArrayList<>();
                parts.add(file.label() != null ? file.label() : "");
                parts.add(tokens(file.tokens()));
                if (file.truncated()) {
                    parts.add(I18n.get("ai.memory.sent.truncated"));
                }
                body.getChildren().add(row(String.join(SEPARATOR, parts), file.text(), rowStyle, fontSize, false));
            }
        }
    }

    private static Label row(String text, String tooltipText, String styleClass, double fontSize, boolean header) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        if (header) {
            label.setStyle("-fx-font-weight: bold;");
        }
        if (fontSize > 0) {
            label.setFont(Font.font(fontSize));
        }
        if (tooltipText != null && !tooltipText.isBlank()) {
            label.setTooltip(new Tooltip(clip(tooltipText)));
        }
        return label;
    }

    private static String clip(String text) {
        return text.length() > TOOLTIP_CHARS ? text.substring(0, TOOLTIP_CHARS) + "…" : text;
    }

    private static String tokens(int count) {
        return I18n.get("ai.memory.sent.tokens", count);
    }

    private static String originText(Origin origin) {
        return switch (origin) {
            case ORGANIZATION -> I18n.get("ai.memory.sent.origin.organization");
            case GLOBAL -> I18n.get("ai.memory.sent.origin.global");
            case CONNECTION -> I18n.get("ai.memory.sent.origin.connection");
            default -> "";
        };
    }
}
