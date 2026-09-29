package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The analysis diagram, large: a resizable, non-modal window with the shared diagram viewer, its
 * zoom controls plus "100 %", Ctrl/Cmd + / − / 0 (fit) / 1 (100 %), Ctrl/Cmd + mouse wheel, and
 * panning by dragging or with the scroll bars. It shows the source the analysis panel already has
 * — the diagram is re-rendered offline from its Mermaid source, never requested from the AI again.
 *
 * <p>The owning editor keeps one instance and closes it with itself; the window remembers its
 * size and position like every korTTY dialog.</p>
 */
final class SnippetDiagramZoomWindow extends ThemeAwareDialog<Void> {

    private final SnippetDiagramView diagramView;
    private SnippetDiagramView.DiagramSource source;

    SnippetDiagramZoomWindow(Window owner, Consumer<SnippetDiagramView.CodeNavigationTarget> codeNavigationHandler) {
        setTitle(I18n.get("snippets.ai.diagram.zoomWindow.title"));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        diagramView = new SnippetDiagramView(
            () -> CompletableFuture.completedFuture(source), false, codeNavigationHandler);
        diagramView.showActualSizeButton();
        VBox root = new VBox(diagramView);
        root.setPadding(new Insets(10));
        VBox.setVgrow(diagramView, Priority.ALWAYS);
        getDialogPane().setId("snippet-diagram-zoom-window");
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(1100, 800);
        setResultConverter(buttonType -> null);
        getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::handleZoomKeys);
        setOnHidden(event -> diagramView.dispose());
    }

    /** Shows {@code diagram} (again) and brings the window to the front. */
    void showDiagram(SnippetDiagramView.DiagramSource diagram) {
        if (diagram == null) {
            return;
        }
        this.source = diagram;
        diagramView.showCached(diagram);
        if (isShowing()) {
            revealDialogOrHost();
        } else {
            show();
        }
    }

    /** Switches the open window to {@code diagram} without taking the focus (the shown analysis entry changed). */
    void followDiagram(SnippetDiagramView.DiagramSource diagram) {
        if (diagram == null || !isShowing()) {
            return;
        }
        this.source = diagram;
        diagramView.showCached(diagram);
    }

    /** The source shown (tests). */
    SnippetDiagramView.DiagramSource source() {
        return source;
    }

    /** The viewer (tests). */
    SnippetDiagramView diagramView() {
        return diagramView;
    }

    private void handleZoomKeys(KeyEvent event) {
        if (!event.isShortcutDown()) {
            return;
        }
        KeyCode code = event.getCode();
        if (code == KeyCode.PLUS || code == KeyCode.EQUALS || code == KeyCode.ADD) {
            diagramView.zoomIn();
        } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
            diagramView.zoomOut();
        } else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
            diagramView.zoomToFit();
        } else if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1) {
            diagramView.zoomToActualSize();
        } else {
            return;
        }
        event.consume();
    }
}
