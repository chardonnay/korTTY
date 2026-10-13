package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalLogBadge;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Renders the production badge components: five proposals, 30 glyphs each, no test doubles. */
public final class LogfileBadgePreviewSmoke {
    public static void main(String[] args) throws Exception {
        GlobalSettings settings = new GlobalSettings(); settings.setLanguage("de");
        LanguageManager.getInstance().initialize(settings);
        Path output = Path.of("build/logfile-preview").toAbsolutePath();
        Files.createDirectories(output);
        CompletableFuture<java.awt.image.BufferedImage> captured = new CompletableFuture<>();
        Platform.startup(() -> {
            VBox root = new VBox(18);
            root.setPadding(new Insets(20));
            root.setStyle("-fx-background-color: #f4f4f4;");
            int count = 0;
            String[] colors = {"#c08020", "#4488cc", "#cc5555", "#9966cc", "#339977", "#3399aa"};
            for (var style : TerminalLogBadge.Style.values()) {
                Label heading = new Label((style.ordinal() + 1) + " · " + I18n.get("logfile.badges.style." + style));
                heading.setStyle("-fx-font-size: 17; -fx-font-weight: bold;");
                GridPane grid = new GridPane(); grid.setHgap(14); grid.setVgap(10);
                for (int i = 0; i < TerminalLogBadge.ICONS.size(); i++) {
                    String icon = TerminalLogBadge.ICONS.get(i);
                    String name = i == 0 ? "Hold" : I18n.get("logfile.badges.icon." + icon);
                    var badge = new TerminalLogBadge(i == 0 ? "hold" : "preview-" + i, name, icon, colors[i % colors.length], style);
                    Label label = LogfileBadgeView.create(badge);
                    label.setMinWidth(138);
                    grid.add(label, i % 6, i / 6); count++;
                }
                root.getChildren().addAll(heading, grid);
            }
            if (count != 150) { captured.completeExceptionally(new AssertionError("Expected 150 badges")); return; }
            Stage stage = new Stage(); stage.setScene(new Scene(root)); stage.setTitle("korTTY · Badge-Vorschläge");
            stage.show();
            Platform.runLater(() -> {
                try {
                    root.applyCss(); root.layout();
                    captured.complete(SwingFXUtils.fromFXImage(root.snapshot(null, null), null));
                } catch (RuntimeException e) { captured.completeExceptionally(e); }
                finally { stage.close(); }
            });
        });
        try { ImageIO.write(captured.get(30, TimeUnit.SECONDS), "png", output.resolve("badges-five-styles.png").toFile()); }
        finally { Platform.exit(); }
        System.out.println("Rendered 150 production badges: " + output.resolve("badges-five-styles.png"));
    }
}
