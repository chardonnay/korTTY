package de.kortty.ui;

import de.kortty.model.TerminalLogBadge;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Edits labels from an already loaded library; persistence belongs to the manager's worker. */
final class LogfileBadgeDialog extends ThemeAwareDialog<LogfileBadgeDialog.Choice> {
    record Choice(List<TerminalLogBadge> selected, List<TerminalLogBadge> library) { }
    private final Map<String, TerminalLogBadge> library = new LinkedHashMap<>();
    private final Map<String, CheckBox> checks = new LinkedHashMap<>();
    private final FlowPane pinned = new FlowPane(8, 8);

    LogfileBadgeDialog(Window owner, List<TerminalLogBadge> saved, List<TerminalLogBadge> selected) {
        if (owner != null) { initOwner(owner); }
        setTitle(I18n.get("logfile.badges.title"));
        setHeaderText(I18n.get("logfile.badges.header"));
        for (var badge : TerminalLogBadge.defaults()) {
            library.put(badge.id(), new TerminalLogBadge(badge.id(), I18n.get("logfile.badges." + badge.id()),
                badge.icon(), badge.color(), badge.style()));
        }
        saved.forEach(b -> library.put(b.id(), b));
        selected.forEach(b -> library.put(b.id(), b));
        library.values().forEach(b -> addCheck(b, selected.stream().anyMatch(s -> s.id().equals(b.id()))));

        TextField name = new TextField();
        name.setPromptText(I18n.get("logfile.badges.name"));
        name.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 40 ? change : null));
        ComboBox<TerminalLogBadge.Style> style = new ComboBox<>();
        style.getItems().setAll(TerminalLogBadge.Style.values());
        style.setValue(TerminalLogBadge.Style.SOFT);
        style.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(TerminalLogBadge.Style value) { return value == null ? "" : I18n.get("logfile.badges.style." + value); }
            @Override public TerminalLogBadge.Style fromString(String text) { return null; }
        });
        ColorPicker color = new ColorPicker(Color.web("#4488cc"));
        ToggleGroup symbols = new ToggleGroup();
        FlowPane icons = new FlowPane(6, 6);
        icons.setPrefWrapLength(460);
        for (String icon : TerminalLogBadge.ICONS) {
            ToggleButton button = new ToggleButton();
            button.getStyleClass().add("button");
            button.styleProperty().bind(Bindings.when(button.selectedProperty())
                .then("-fx-border-color: #4488cc; -fx-border-radius: 3;")
                .otherwise("-fx-border-color: transparent; -fx-border-radius: 3;"));
            button.setGraphic(ToolbarIcons.create(icon, button.textFillProperty(), new SimpleDoubleProperty(20)));
            button.setAccessibleText(I18n.get("logfile.badges.icon." + icon));
            button.setTooltip(new Tooltip(button.getAccessibleText()));
            button.setUserData(icon);
            button.setToggleGroup(symbols);
            icons.getChildren().add(button);
        }
        symbols.selectToggle(symbols.getToggles().get(0));
        HBox preview = new HBox();
        Runnable refresh = () -> {
            if (symbols.getSelectedToggle() == null) { return; }
            String hex = String.format(java.util.Locale.ROOT, "#%02x%02x%02x", (int)(color.getValue().getRed()*255),
                (int)(color.getValue().getGreen()*255), (int)(color.getValue().getBlue()*255));
            preview.getChildren().setAll(LogfileBadgeView.create(new TerminalLogBadge("preview",
                name.getText().isBlank() ? I18n.get("logfile.badges.preview") : name.getText().strip(),
                symbols.getSelectedToggle().getUserData().toString(), hex, style.getValue())));
        };
        name.textProperty().addListener((o,a,b) -> refresh.run());
        color.valueProperty().addListener((o,a,b) -> refresh.run());
        style.valueProperty().addListener((o,a,b) -> refresh.run());
        symbols.selectedToggleProperty().addListener((o,a,b) -> refresh.run());
        refresh.run();
        Button add = new Button(I18n.get("logfile.badges.create"));
        add.disableProperty().bind(Bindings.createBooleanBinding(() -> name.getText().isBlank()
            || name.getText().chars().anyMatch(Character::isISOControl) || symbols.getSelectedToggle() == null
            || library.values().stream().anyMatch(b -> b.name().equalsIgnoreCase(name.getText().strip())),
            name.textProperty(), symbols.selectedToggleProperty()));
        add.setOnAction(event -> {
            String hex = String.format(java.util.Locale.ROOT, "#%02x%02x%02x", (int)(color.getValue().getRed()*255),
                (int)(color.getValue().getGreen()*255), (int)(color.getValue().getBlue()*255));
            var badge = new TerminalLogBadge(UUID.randomUUID().toString(), name.getText(),
                symbols.getSelectedToggle().getUserData().toString(), hex, style.getValue());
            library.put(badge.id(), badge);
            addCheck(badge, true);
            name.clear();
        });
        VBox content = new VBox(12, pinned, new Separator(), new Label(I18n.get("logfile.badges.custom")),
            name, new HBox(8, style, color), icons, preview, add);
        content.setPadding(new Insets(12));
        content.setPrefWidth(500);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(500);
        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        setResultConverter(button -> button == ButtonType.OK ? new Choice(
            library.values().stream().filter(b -> checks.get(b.id()).isSelected()).toList(),
            new ArrayList<>(library.values())) : null);
    }
    private void addCheck(TerminalLogBadge badge, boolean selected) {
        CheckBox check = new CheckBox();
        check.setGraphic(LogfileBadgeView.create(badge));
        check.setAccessibleText(badge.name());
        check.setSelected(selected);
        checks.put(badge.id(), check);
        pinned.getChildren().add(check);
    }
}
