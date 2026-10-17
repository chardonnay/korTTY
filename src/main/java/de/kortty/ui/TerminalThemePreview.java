package de.kortty.ui;

import de.kortty.model.Theme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.List;
import java.util.function.Supplier;

/**
 * A mock terminal screen painted in a theme: its background, foreground and cursor colours, its
 * font, and the sixteen ANSI colours of the Colors tab, applied to a sample session
 * ({@link TerminalPreviewModel#sampleSession}). Colours are set as {@link Color} objects, never as
 * CSS strings, so the preview looks the same in the Normal and the AtlantaFX designs.
 */
public final class TerminalThemePreview extends VBox {

    private static final Color FALLBACK_BG = Color.web("#1E1E1E");
    private static final Color FALLBACK_FG = Color.web("#CCCCCC");

    private final VBox screen = new VBox(2);
    private final Label hint = new Label(I18n.get("theme.preview.hint"));
    private Supplier<TerminalPreviewModel.Palette> palette;
    private Theme theme;
    private Font font = Font.font("Monospaced", 13);

    /**
     * @param palette where the ANSI colours come from, asked again on every {@link #refresh()} so
     *                unsaved Colors-tab changes show up as soon as the Themes tab is shown
     */
    public TerminalThemePreview(Supplier<TerminalPreviewModel.Palette> palette) {
        super(6);
        this.palette = palette;
        screen.setPadding(new Insets(12, 14, 12, 14));
        screen.setMinHeight(Region.USE_PREF_SIZE);
        ScrollPane scroll = new ScrollPane(screen);
        scroll.setFitToHeight(false);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefViewportHeight(360);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        getChildren().addAll(scroll, hint);
        paint();
    }

    /** Shows {@code theme}; {@code font} is the font the terminal would use for it (theme or global). */
    public void setTheme(Theme theme, Font font) {
        this.theme = theme;
        if (font != null) {
            this.font = font;
        }
        paint();
    }

    public void setPalette(Supplier<TerminalPreviewModel.Palette> palette) {
        this.palette = palette;
        paint();
    }

    /** Repaints with the current theme and a fresh palette lookup. */
    public void refresh() {
        paint();
    }

    /** The painted lines; for tests and smokes. */
    List<javafx.scene.Node> lines() {
        return List.copyOf(screen.getChildren());
    }

    private void paint() {
        screen.getChildren().clear();
        Color bg = color(theme != null ? theme.getBackgroundColor() : null, FALLBACK_BG);
        Color fg = color(theme != null ? theme.getForegroundColor() : null, FALLBACK_FG);
        Color cursor = color(theme != null ? theme.getCursorColor() : null, fg);
        screen.setBackground(new Background(new BackgroundFill(bg, new CornerRadii(6), Insets.EMPTY)));
        screen.setBorder(new Border(new BorderStroke(fg.deriveColor(0, 1, 1, 0.25), BorderStrokeStyle.SOLID,
            new CornerRadii(6), new BorderWidths(1))));
        if (theme == null) {
            Text empty = new Text(I18n.get("theme.preview.empty"));
            empty.setFill(fg);
            empty.setFont(font);
            screen.getChildren().add(empty);
            return;
        }
        TerminalPreviewModel.Palette colors = palette != null ? palette.get() : null;
        if (colors == null) {
            colors = (index, bright) -> de.kortty.core.TerminalPaletteSupport.builtInHex(index, bright);
        }
        Font bold = Font.font(font.getFamily(), FontWeight.BOLD, font.getSize());
        double cellWidth = new Text("M") { { setFont(font); } }.getLayoutBounds().getWidth();
        double cellHeight = new Text("M") { { setFont(font); } }.getLayoutBounds().getHeight();
        TerminalPreviewModel.CursorShape shape = TerminalPreviewModel.cursorShape(theme.getCursorStyle());
        for (TerminalPreviewModel.Line line : TerminalPreviewModel.sampleSession(theme, colors)) {
            HBox row = new HBox(0);
            row.setAlignment(Pos.BOTTOM_LEFT);
            row.setMinHeight(cellHeight);
            for (TerminalPreviewModel.Run run : line.runs()) {
                Text text = new Text(run.text());
                text.setFont(run.bold() ? bold : font);
                text.setFill(run.hex() != null ? color(run.hex(), fg) : fg);
                row.getChildren().add(text);
            }
            if (line.cursorAfter()) {
                row.getChildren().add(cursorNode(shape, cursor, cellWidth, cellHeight));
            }
            screen.getChildren().add(row);
        }
    }

    private static javafx.scene.Node cursorNode(TerminalPreviewModel.CursorShape shape, Color color,
                                                double cellWidth, double cellHeight) {
        Rectangle rect = switch (shape) {
            case BLOCK -> new Rectangle(Math.max(6, cellWidth), Math.max(10, cellHeight * 0.85));
            case UNDERLINE -> new Rectangle(Math.max(6, cellWidth), 2);
            case BAR -> new Rectangle(2, Math.max(10, cellHeight * 0.85));
        };
        rect.setFill(color);
        VBox holder = new VBox(rect);
        holder.setAlignment(Pos.BOTTOM_LEFT);
        holder.setMinHeight(cellHeight);
        holder.setPadding(new Insets(0, 0, shape == TerminalPreviewModel.CursorShape.UNDERLINE ? 2 : 1, 0));
        return holder;
    }

    /** {@code hex} as a colour, or {@code fallback} when it does not parse (imported themes carry odd values). */
    static Color color(String hex, Color fallback) {
        if (!TerminalPreviewModel.isColor(hex)) {
            return fallback;
        }
        try {
            return Color.web(hex.trim());
        } catch (IllegalArgumentException invalid) {
            return fallback;
        }
    }
}
