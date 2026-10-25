package de.kortty.ui;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.CustomOsBadge;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The user's own OS badges: add an image (PNG, JPG, GIF, or SVG, which korTTY renders once with
 * its web engine), rename or delete it. Every badge is stored as a 128-pixel PNG in the
 * {@code os-badges} folder of the configuration directory and listed in the global settings.
 * Deleting a badge leaves the connections that used it without one.
 */
final class OsBadgeManagerDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(OsBadgeManagerDialog.class);
    /** Edge length of a stored badge; drawn at up to 30 px, so 128 keeps HiDPI screens sharp. */
    private static final int STORED_SIZE = 128;

    private final GlobalSettingsManager settingsManager;
    private final ListView<CustomOsBadge> list = new ListView<>();

    OsBadgeManagerDialog(Window owner, GlobalSettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        if (owner != null) {
            initOwner(owner);
        }
        setTitle(I18n.get("connectionManager.os.manager.title"));
        setHeaderText(I18n.get("connectionManager.os.manager.header"));

        list.setPrefSize(420, 280);
        list.setPlaceholder(new Label(I18n.get("connectionManager.os.manager.empty")));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(CustomOsBadge badge, boolean empty) {
                super.updateItem(badge, empty);
                setText(null);
                setGraphic(null);
                if (empty || badge == null) {
                    return;
                }
                OsBadgeSupport.Resolved resolved = OsBadgeSupport.resolve(
                    OsBadgeSupport.CUSTOM_PREFIX + badge.getId(), settings());
                Label name = new Label(badge.getName());
                name.textFillProperty().bind(textFillProperty());
                HBox row = new HBox(10, resolved != null ? OsBadgeSupport.badgeNode(resolved, 28) : new StackPane(), name);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setAccessibleText(badge.getName());
            }
        });

        Button add = button(I18n.get("connectionManager.os.manager.add"), "add", () -> chooseAndAdd());
        Button rename = button(I18n.get("connectionManager.os.manager.rename"), "rename", this::renameSelected);
        Button delete = button(I18n.get("connectionManager.os.manager.delete"), "delete", this::deleteSelected);
        rename.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        HBox buttons = new HBox(8, add, rename, delete);
        Label hint = new Label(I18n.get("connectionManager.os.manager.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-opacity: 0.7;");
        VBox content = new VBox(10, list, buttons, hint);
        VBox.setVgrow(list, Priority.ALWAYS);
        content.setPadding(new Insets(14));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        reload();
    }

    private GlobalSettings settings() {
        return settingsManager != null ? settingsManager.getSettings() : null;
    }

    private void reload() {
        GlobalSettings settings = settings();
        list.getItems().setAll(settings != null ? settings.getCustomOsBadges() : List.of());
    }

    private Button button(String text, String glyph, Runnable action) {
        Button button = new Button(text);
        button.setGraphic(ToolbarIcons.create(glyph, button.textFillProperty(), 16));
        button.setContentDisplay(ContentDisplay.LEFT);
        button.setOnAction(e -> action.run());
        button.setTooltip(new Tooltip(text));
        return button;
    }

    private void chooseAndAdd() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("connectionManager.os.manager.add"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            I18n.get("connectionManager.os.manager.images"), "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg"));
        File file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        String base = file.getName().replaceFirst("\\.[^.]+$", "");
        TextInputDialog ask = new TextInputDialog(base);
        ask.initOwner(getDialogPane().getScene().getWindow());
        ask.setTitle(I18n.get("connectionManager.os.manager.add"));
        ask.setHeaderText(null);
        ask.setContentText(I18n.get("connectionManager.os.manager.name"));
        ask.showAndWait().map(String::trim).filter(name -> !name.isEmpty()).ifPresent(name ->
            render(file.toPath(), image -> store(image, name)));
    }

    /** Hands {@code file} as a square image to {@code done}: SVG through the web engine, other formats as they are. */
    private void render(Path file, Consumer<Image> done) {
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            renderSvg(file, done);
            return;
        }
        Image image = new Image(file.toUri().toString(), STORED_SIZE, STORED_SIZE, true, true);
        if (image.isError()) {
            failed(image.getException());
            return;
        }
        done.accept(image);
    }

    /** JavaFX draws no SVG files, so the web engine renders it once, off screen, and is snapshotted. */
    private void renderSvg(Path file, Consumer<Image> done) {
        WebView web = new WebView();
        web.setPageFill(Color.TRANSPARENT);
        web.setPrefSize(STORED_SIZE, STORED_SIZE);
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        stage.setScene(new Scene(new StackPane(web), STORED_SIZE, STORED_SIZE, Color.TRANSPARENT));
        stage.setX(-10_000);
        stage.setY(-10_000);
        stage.show();
        String html = "<html><body style=\"margin:0;background:transparent\"><img src=\"" + file.toUri()
            + "\" style=\"width:" + STORED_SIZE + "px;height:" + STORED_SIZE + "px;object-fit:contain\"></body></html>";
        web.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                // Give the engine a frame to paint the image before it is snapshotted.
                PauseTransition paint = new PauseTransition(Duration.millis(300));
                paint.setOnFinished(e -> {
                    SnapshotParameters parameters = new SnapshotParameters();
                    parameters.setFill(Color.TRANSPARENT);
                    WritableImage image = web.snapshot(parameters, null);
                    stage.close();
                    done.accept(image);
                });
                paint.play();
            } else if (state == javafx.concurrent.Worker.State.FAILED) {
                stage.close();
                failed(web.getEngine().getLoadWorker().getException());
            }
        });
        web.getEngine().loadContent(html);
    }

    private void store(Image image, String name) {
        GlobalSettings settings = settings();
        if (settings == null) {
            return;
        }
        String id = UUID.randomUUID().toString();
        try {
            Path folder = OsBadgeSupport.customDirectory();
            Files.createDirectories(folder);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", folder.resolve(id + ".png").toFile());
        } catch (Exception e) {
            failed(e);
            return;
        }
        List<CustomOsBadge> badges = new ArrayList<>(settings.getCustomOsBadges());
        badges.add(new CustomOsBadge(id, name, id + ".png"));
        save(badges);
    }

    private void renameSelected() {
        CustomOsBadge selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        TextInputDialog ask = new TextInputDialog(selected.getName());
        ask.initOwner(getDialogPane().getScene().getWindow());
        ask.setTitle(I18n.get("connectionManager.os.manager.rename"));
        ask.setHeaderText(null);
        ask.setContentText(I18n.get("connectionManager.os.manager.name"));
        ask.showAndWait().map(String::trim).filter(name -> !name.isEmpty()).ifPresent(name -> {
            List<CustomOsBadge> badges = new ArrayList<>();
            for (CustomOsBadge badge : settings().getCustomOsBadges()) {
                badges.add(badge.getId().equals(selected.getId())
                    ? new CustomOsBadge(badge.getId(), name, badge.getFile()) : badge);
            }
            save(badges);
        });
    }

    private void deleteSelected() {
        CustomOsBadge selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            I18n.get("connectionManager.os.manager.deleteConfirm", selected.getName()), ButtonType.OK, ButtonType.CANCEL);
        DialogThemeHelper.applyTheme(confirm);
        confirm.initOwner(getDialogPane().getScene().getWindow());
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        List<CustomOsBadge> badges = new ArrayList<>(settings().getCustomOsBadges());
        badges.removeIf(badge -> badge.getId().equals(selected.getId()));
        if (save(badges) && selected.getFile() != null) {
            try {
                Files.deleteIfExists(OsBadgeSupport.customDirectory().resolve(selected.getFile()));
            } catch (Exception e) {
                logger.warn("Could not delete the badge image {}", selected.getFile(), e);
            }
        }
    }

    /** Stores {@code badges}; a failed save puts the previous ones back and says so. */
    private boolean save(List<CustomOsBadge> badges) {
        GlobalSettings settings = settings();
        List<CustomOsBadge> previous = new ArrayList<>(settings.getCustomOsBadges());
        settings.setCustomOsBadges(badges);
        try {
            settingsManager.save();
            reload();
            return true;
        } catch (Exception e) {
            settings.setCustomOsBadges(previous);
            failed(e);
            return false;
        }
    }

    private void failed(Throwable error) {
        logger.warn("Could not add or change an OS badge", error);
        Alert alert = new Alert(Alert.AlertType.ERROR,
            I18n.get("connectionManager.os.manager.failed", error != null ? error.getMessage() : ""), ButtonType.OK);
        DialogThemeHelper.applyTheme(alert);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
