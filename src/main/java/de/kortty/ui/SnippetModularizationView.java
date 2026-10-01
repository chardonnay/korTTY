package de.kortty.ui;

import de.kortty.core.SnippetModularizationSupport.ModularizationPlan;
import de.kortty.core.SnippetModularizationSupport.ModuleFile;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * The modularization proposal shown with an analysis: the AI's rationale and, when it recommends
 * a split, the proposed file tree (entry point, executable marks, each file's purpose) with
 * "Apply modularization…".
 */
final class SnippetModularizationView {

    static final String VIEW_ID = "snippet-modularization-view";
    static final String APPLY_ID = "snippet-modularization-apply";

    private SnippetModularizationView() {
    }

    /** @param onApply starts the generation; {@code null} when the plan cannot be applied */
    static Node build(ModularizationPlan plan, Runnable onApply) {
        Label title = new Label(I18n.get(plan.isApplicable()
            ? "snippets.modularize.proposed" : "snippets.modularize.notRecommended"));
        title.setStyle("-fx-font-weight: bold;");
        Label rationale = new Label(plan.rationale());
        rationale.setWrapText(true);
        rationale.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(6, title, rationale);
        box.setId(VIEW_ID);
        box.setStyle("-fx-border-color: rgba(56,189,248,0.55); -fx-border-radius: 6; -fx-padding: 8;");
        if (plan.isApplicable()) {
            TreeView<String> tree = new TreeView<>(buildTree(plan));
            tree.setShowRoot(false);
            tree.setPrefHeight(Math.min(260, 28 + plan.files().size() * 26));
            tree.setMinHeight(90);
            box.getChildren().add(tree);
            if (onApply != null) {
                Button apply = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.modularize.apply"));
                apply.setId(APPLY_ID);
                apply.setOnAction(event -> onApply.run());
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(8, spacer, apply);
                row.setAlignment(Pos.CENTER_RIGHT);
                box.getChildren().add(row);
            }
        }
        return box;
    }

    private static TreeItem<String> buildTree(ModularizationPlan plan) {
        TreeItem<String> root = new TreeItem<>("");
        root.setExpanded(true);
        Map<String, TreeItem<String>> directories = new HashMap<>();
        for (ModuleFile file : plan.files()) {
            TreeItem<String> parent = root;
            String[] segments = file.path().split("/");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                prefix.append(segments[i]).append('/');
                String key = prefix.toString();
                TreeItem<String> directory = directories.get(key);
                if (directory == null) {
                    directory = new TreeItem<>("📁 " + segments[i]);
                    directory.setExpanded(true);
                    directories.put(key, directory);
                    parent.getChildren().add(directory);
                }
                parent = directory;
            }
            StringBuilder label = new StringBuilder(file.entryPoint() ? "▶ " : "• ").append(file.fileName());
            if (file.executable()) {
                label.append("  ⚙");
            }
            if (!file.purpose().isBlank()) {
                label.append(" — ").append(file.purpose());
            }
            parent.getChildren().add(new TreeItem<>(label.toString()));
        }
        return root;
    }
}
