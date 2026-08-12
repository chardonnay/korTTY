package de.kortty.ui;

import de.kortty.model.AiSkill;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A row that surfaces which AI skills are included in an AI operation and lets the user change the set:
 * a chip list of the currently-included skills, an auto-/manually-selected badge, and an "edit" button
 * that opens a <b>searchable</b> checkbox picker over all saved skills. Every change is reported through
 * {@code onChange}; the host decides when it takes effect (the code-analysis window applies the new set
 * on the next re-run rather than immediately).
 */
final class AiSkillPickerControl extends HBox {

    private final List<AiSkill> allSkills;
    private final Set<String> selectedIds;
    private final Consumer<Set<String>> onChange;

    private final FlowPane chips = new FlowPane(6, 6);
    private final Label modeBadge = new Label();
    private boolean userEdited;

    AiSkillPickerControl(List<AiSkill> allSkills, Set<String> selectedIds, boolean autoSelected,
                         Consumer<Set<String>> onChange) {
        this.allSkills = allSkills != null ? allSkills : List.of();
        this.selectedIds = new LinkedHashSet<>(selectedIds != null ? selectedIds : Set.of());
        this.userEdited = !autoSelected;
        this.onChange = onChange;

        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(I18n.get("snippets.ai.analysis.skills.label"));
        title.setStyle("-fx-font-weight: bold;");
        updateModeBadge();

        chips.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(chips, Priority.ALWAYS);
        refreshChips();

        Button editButton = new Button(I18n.get("snippets.ai.analysis.skills.edit"));
        editButton.setTooltip(new Tooltip(I18n.get("snippets.ai.analysis.skills.edit.tooltip")));
        editButton.setDisable(this.allSkills.isEmpty());
        editButton.setOnAction(event -> showPicker(editButton));

        getChildren().addAll(title, modeBadge, chips, editButton);
    }

    private void updateModeBadge() {
        modeBadge.setText(I18n.get(userEdited
            ? "snippets.ai.analysis.skills.manual"
            : "snippets.ai.analysis.skills.auto"));
        modeBadge.setStyle("-fx-opacity: 0.7; -fx-font-size: 0.8462em;");
    }

    private void refreshChips() {
        chips.getChildren().clear();
        if (selectedIds.isEmpty()) {
            Label none = new Label(I18n.get("snippets.ai.analysis.skills.none"));
            none.setStyle("-fx-opacity: 0.6; -fx-font-style: italic;");
            chips.getChildren().add(none);
            return;
        }
        for (AiSkill skill : allSkills) {
            if (skill.getId() != null && selectedIds.contains(skill.getId())) {
                chips.getChildren().add(makeChip(skillName(skill), skill.getDescription()));
            }
        }
    }

    private Label makeChip(String text, String tooltip) {
        Label chip = new Label(text);
        chip.setStyle("-fx-background-color: rgba(59,130,246,0.18); -fx-background-radius: 999;"
            + " -fx-padding: 2 9 2 9; -fx-font-size: 0.8462em;");
        if (tooltip != null && !tooltip.isBlank()) {
            chip.setTooltip(new Tooltip(tooltip));
        }
        return chip;
    }

    private void showPicker(Button anchor) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setAutoFix(true);

        TextField search = new TextField();
        search.setPromptText(I18n.get("snippets.ai.analysis.skills.search"));

        VBox list = new VBox(2);
        list.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(220);
        scroll.setPrefViewportWidth(320);

        search.textProperty().addListener((obs, was, isNow) -> populateList(list, isNow));
        populateList(list, "");

        VBox container = new VBox(6, search, scroll);
        container.setPadding(new Insets(8));
        container.setStyle("-fx-background-color: -fx-control-inner-background;"
            + " -fx-border-color: rgba(128,128,128,0.4); -fx-border-radius: 6; -fx-background-radius: 6;"
            + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 12, 0, 0, 4);");
        popup.getContent().add(container);

        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
            search.requestFocus();
        }
    }

    private void populateList(VBox list, String query) {
        list.getChildren().clear();
        String needle = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        boolean any = false;
        for (AiSkill skill : allSkills) {
            if (!matches(skill, needle)) {
                continue;
            }
            any = true;
            String id = skill.getId();
            CheckBox check = new CheckBox(skillName(skill));
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                check.setTooltip(new Tooltip(skill.getDescription()));
            }
            check.setSelected(id != null && selectedIds.contains(id));
            check.selectedProperty().addListener((obs, was, isNow) -> {
                if (id == null) {
                    return;
                }
                if (isNow) {
                    selectedIds.add(id);
                } else {
                    selectedIds.remove(id);
                }
                userEdited = true;
                updateModeBadge();
                refreshChips();
                if (onChange != null) {
                    onChange.accept(new LinkedHashSet<>(selectedIds));
                }
            });
            list.getChildren().add(check);
        }
        if (!any) {
            Label empty = new Label(I18n.get("snippets.ai.analysis.skills.searchEmpty"));
            empty.setStyle("-fx-opacity: 0.6; -fx-padding: 6;");
            list.getChildren().add(empty);
        }
    }

    private static boolean matches(AiSkill skill, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return contains(skill.getName(), needle)
            || contains(skill.getDescription(), needle)
            || contains(skill.getTagsAsString(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String skillName(AiSkill skill) {
        return skill.getName() != null && !skill.getName().isBlank() ? skill.getName() : skill.getId();
    }
}
