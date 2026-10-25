package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.CustomOsBadge;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The OS badges a connection can show: korTTY's built-in logos ({@link OsBadges}) and the images the
 * user added ({@link CustomOsBadge}, a PNG in the {@code os-badges} folder of the configuration
 * directory). A connection stores a built-in id or {@code custom:<id>}; an id that no longer
 * resolves shows no badge. FX thread only for the nodes.
 */
final class OsBadgeSupport {

    static final String CUSTOM_PREFIX = "custom:";

    /** A badge a connection id resolved to: a built-in one, or the image of a custom one. */
    record Resolved(String id, String name, OsBadges.Badge builtIn, Path image) {
        boolean custom() {
            return builtIn == null;
        }
    }

    private OsBadgeSupport() {
    }

    /** The folder the custom badges' PNGs live in. */
    static Path customDirectory() {
        return KorTTYApplication.getConfigDirectory().resolve("os-badges");
    }

    /** The badge {@code id} names, or null for none or an id that no longer exists. */
    static Resolved resolve(String id, GlobalSettings settings) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (id.startsWith(CUSTOM_PREFIX)) {
            String customId = id.substring(CUSTOM_PREFIX.length());
            if (settings != null) {
                for (CustomOsBadge badge : settings.getCustomOsBadges()) {
                    if (customId.equals(badge.getId()) && badge.getFile() != null) {
                        Path image = customDirectory().resolve(badge.getFile());
                        return Files.isRegularFile(image) ? new Resolved(id, badge.getName(), null, image) : null;
                    }
                }
            }
            return null;
        }
        Optional<OsBadges.Badge> builtIn = OsBadges.builtIn(id);
        return builtIn.map(badge -> new Resolved(id, badge.name(), badge, null)).orElse(null);
    }

    /** Every badge to choose from: the built-in ones by family, then the user's own. */
    static List<Resolved> all(GlobalSettings settings) {
        List<Resolved> all = new ArrayList<>();
        for (OsBadges.Badge badge : OsBadges.builtIn()) {
            all.add(new Resolved(badge.id(), badge.name(), badge, null));
        }
        if (settings != null) {
            for (CustomOsBadge badge : settings.getCustomOsBadges()) {
                Resolved resolved = resolve(CUSTOM_PREFIX + badge.getId(), settings);
                if (resolved != null) {
                    all.add(resolved);
                }
            }
        }
        return all;
    }

    /** The badge in a {@code size} box: the drawn logo, or the user's image with rounded corners. */
    static Node badgeNode(Resolved badge, double size) {
        if (badge.builtIn() != null) {
            Node node = OsBadges.node(badge.builtIn(), size);
            node.setAccessibleText(badge.name());
            return node;
        }
        ImageView view = new ImageView(new Image(badge.image().toUri().toString(), size * 2, size * 2, true, true));
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(size * 0.42);
        clip.setArcHeight(size * 0.42);
        view.setClip(clip);
        StackPane box = new StackPane(view);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        box.setAccessibleText(badge.name());
        return box;
    }

    /** The family heading a badge is listed under in the picker. */
    static String familyName(Resolved badge) {
        if (badge.custom()) {
            return I18n.get("connectionManager.os.family.custom");
        }
        return switch (badge.builtIn().family()) {
            case WINDOWS -> "Windows";
            case LINUX -> "Linux";
            case UNIX -> "Unix";
            case BSD -> "BSD";
        };
    }

    /**
     * A drop-down of every badge with its picture and name, the current one {@code currentId} chosen;
     * its first entry is "No symbol". The value is null for none.
     */
    static ComboBox<Resolved> picker(String currentId, GlobalSettings settings) {
        ComboBox<Resolved> combo = new ComboBox<>();
        combo.getItems().add(null);
        combo.getItems().addAll(all(settings));
        Resolved current = resolve(currentId, settings);
        if (current != null) {
            combo.getItems().stream().filter(item -> item != null && item.id().equals(current.id()))
                .findFirst().ifPresent(combo::setValue);
        }
        combo.setCellFactory(list -> new BadgeCell(true));
        combo.setButtonCell(new BadgeCell(false));
        combo.setVisibleRowCount(12);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setAccessibleText(I18n.get("connectionManager.inspector.os"));
        return combo;
    }

    /** A picker entry: badge and name, in the list with its family before it. */
    private static final class BadgeCell extends ListCell<Resolved> {
        private final boolean withFamily;

        BadgeCell(boolean withFamily) {
            this.withFamily = withFamily;
        }

        @Override
        protected void updateItem(Resolved item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(null);
            if (empty) {
                return;
            }
            if (item == null) {
                setText(I18n.get("connectionManager.os.none"));
                return;
            }
            Label name = new Label(item.name());
            name.textFillProperty().bind(textFillProperty());
            HBox row = new HBox(8, badgeNode(item, 18), name);
            row.setAlignment(Pos.CENTER_LEFT);
            if (withFamily) {
                Label family = new Label(familyName(item));
                family.textFillProperty().bind(ConnectionRowGraphics.muted(textFillProperty(), 0.6));
                family.setStyle("-fx-font-size: 0.85em;");
                row.getChildren().add(family);
            }
            setGraphic(row);
            setAccessibleText(item.name());
        }
    }
}
