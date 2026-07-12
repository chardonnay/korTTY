package de.kortty.ui;

import de.kortty.core.SnippetDiagramSupport;
import de.kortty.model.SnippetDiagram;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Shows persisted snippet Mermaid diagrams using the shared static-SVG viewer. */
public class SnippetDiagramDialog extends ThemeAwareDialog<Void> {

    private static final double LIST_WIDTH_PADDING = 42.0;
    private static final double EMPTY_LIST_WIDTH = 140.0;

    private final String currentContent;
    private final Consumer<SnippetDiagram> regenerateHandler;
    private final Consumer<CodeNavigationTarget> codeNavigationHandler;
    private final ListView<SnippetDiagram> diagramListView;
    private final SnippetDiagramView diagramView;
    private final Label stateLabel = new Label();
    private final AtomicReference<SnippetDiagram> selectedDiagram = new AtomicReference<>();

    public SnippetDiagramDialog(
        Window owner,
        List<SnippetDiagram> diagrams,
        String currentContent,
        String snippetName,
        Consumer<SnippetDiagram> regenerateHandler,
        Runnable newDiagramHandler,
        Consumer<CodeNavigationTarget> codeNavigationHandler) {

        this.currentContent = currentContent != null ? currentContent : "";
        this.regenerateHandler = regenerateHandler;
        this.codeNavigationHandler = codeNavigationHandler;

        setTitle(I18n.get("snippets.ai.diagram.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        List<SnippetDiagram> safeDiagrams = diagrams != null ? diagrams : List.of();
        diagramListView = new ListView<>(FXCollections.observableArrayList(safeDiagrams));
        diagramListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SnippetDiagram item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : getDiagramTypeLabel(item));
            }
        });
        double listWidth = configureListWidth(safeDiagrams);

        diagramView = new SnippetDiagramView(
            this::selectedSource,
            false,
            target -> navigateToCode(target.startLine(), target.endLine()));

        Button regenerate = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX
            + I18n.get("snippets.ai.diagram.regenerate"));
        regenerate.setOnAction(event -> {
            SnippetDiagram selected = selectedDiagram.get();
            if (selected != null && this.regenerateHandler != null) {
                close();
                this.regenerateHandler.accept(selected);
            }
        });
        regenerate.disableProperty().bind(diagramListView.getSelectionModel().selectedItemProperty().isNull());

        HBox actions = new HBox(8, regenerate);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (newDiagramHandler != null) {
            Button create = new Button(SnippetAiDialogSupport.AI_ACTION_PREFIX + I18n.get("snippets.ai.diagram.new"));
            create.setOnAction(event -> {
                close();
                newDiagramHandler.run();
            });
            actions.getChildren().add(create);
        }

        String displayName = snippetName != null && !snippetName.isBlank()
            ? snippetName.trim()
            : I18n.get("snippets.ai.diagram.script.unnamed");
        Label scriptLabel = new Label(I18n.get("snippets.ai.diagram.script", displayName));
        stateLabel.setWrapText(true);
        VBox right = new VBox(8, scriptLabel, stateLabel, actions, diagramView);
        VBox.setVgrow(diagramView, Priority.ALWAYS);

        SplitPane split = new SplitPane(diagramListView, right);
        SplitPane.setResizableWithParent(diagramListView, false);
        split.widthProperty().addListener((observable, oldWidth, newWidth) ->
            setDivider(split, listWidth));
        Platform.runLater(() -> setDivider(split, listWidth));

        VBox root = new VBox(split);
        root.setPadding(new Insets(14));
        VBox.setVgrow(split, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        setResultConverter(buttonType -> null);
        getDialogPane().setPrefWidth(980);
        getDialogPane().setPrefHeight(700);
        setOnHidden(event -> diagramView.dispose());

        diagramListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            selectedDiagram.set(newValue);
            showSelectedDiagram(newValue);
        });
        if (safeDiagrams.isEmpty()) {
            showSelectedDiagram(null);
        } else {
            diagramListView.getSelectionModel().selectFirst();
        }
    }

    private CompletableFuture<SnippetDiagramView.DiagramSource> selectedSource() {
        SnippetDiagram diagram = selectedDiagram.get();
        if (diagram == null || diagram.getMermaidSource() == null || diagram.getMermaidSource().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(new SnippetDiagramView.DiagramSource(
            diagram.getMermaidSource(), currentContent, sourceCodeReferences(diagram)));
    }

    private void showSelectedDiagram(SnippetDiagram diagram) {
        if (diagram == null) {
            diagramView.clear();
            stateLabel.setText(I18n.get("snippets.ai.diagram.empty"));
            return;
        }
        stateLabel.setText(SnippetDiagramSupport.isStale(diagram, currentContent)
            ? I18n.get("snippets.ai.diagram.stale")
            : I18n.get("snippets.ai.diagram.current"));
        diagramView.reload();
    }

    private static List<SnippetDiagramSupport.SourceCodeReference> sourceCodeReferences(SnippetDiagram diagram) {
        if (diagram == null || diagram.getCodeReferences().isEmpty()) {
            return List.of();
        }
        List<SnippetDiagramSupport.SourceCodeReference> result = new ArrayList<>();
        for (SnippetDiagram.CodeReference reference : diagram.getCodeReferences()) {
            if (reference != null) {
                result.add(new SnippetDiagramSupport.SourceCodeReference(
                    reference.getNodeId(), reference.getLabel(), reference.getStartLine(), reference.getEndLine()));
            }
        }
        return List.copyOf(result);
    }

    private void navigateToCode(int startLine, int endLine) {
        if (codeNavigationHandler != null) {
            codeNavigationHandler.accept(new CodeNavigationTarget(startLine, endLine));
        }
    }

    private double configureListWidth(List<SnippetDiagram> diagrams) {
        double width = EMPTY_LIST_WIDTH;
        for (SnippetDiagram diagram : diagrams) {
            width = Math.max(width, Math.ceil(new Text(getDiagramTypeLabel(diagram)).getLayoutBounds().getWidth()
                + LIST_WIDTH_PADDING));
        }
        diagramListView.setMinWidth(width);
        diagramListView.setPrefWidth(width);
        diagramListView.setMaxWidth(width);
        return width;
    }

    private static void setDivider(SplitPane pane, double listWidth) {
        if (pane.getWidth() > 0) {
            pane.setDividerPositions(Math.min(0.9, listWidth / pane.getWidth()));
        }
    }

    private static String getDiagramTypeLabel(SnippetDiagram diagram) {
        if (diagram == null) {
            return "";
        }
        if (SnippetDiagram.TYPE_LOGICAL_STRUCTURE.equals(diagram.getType())) {
            return I18n.get("snippets.ai.diagram.type.logicalStructure");
        }
        return diagram.getType() != null && !diagram.getType().isBlank()
            ? diagram.getType()
            : I18n.get("snippets.ai.diagram.type.unknown");
    }

    public record CodeNavigationTarget(int startLine, int endLine) {
    }
}
