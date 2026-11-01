package de.kortty.ui;

import de.kortty.KorTTYApplication;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * The memory of one connection, opened from the connection editor: the Memory editor with its scope set to that
 * connection. Non-modal, like the other tool windows; the settings it shows are saved when it closes.
 */
public final class AiMemoryDialog extends ThemeAwareDialog<Void> {

    /**
     * @param connectionId   the connection's stable id, which names its memory file
     * @param connectionName the name shown in the title
     */
    public AiMemoryDialog(Window owner, String connectionId, String connectionName) {
        setTitle(I18n.get("settings.ai.memory.dialog.title", connectionName));
        setResizable(true);
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }
        KorTTYApplication app = KorTTYApplication.getInstance();
        AiMemoryPane pane = new AiMemoryPane(app, owner,
            new AiMemoryPane.ScopeChoice(connectionId, connectionName));
        ScrollPane scroller = new ScrollPane(pane);
        scroller.setFitToWidth(true);
        scroller.setPadding(new Insets(4));
        getDialogPane().setContent(scroller);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefSize(900, 700);
        setResultConverter(button -> null);
        setOnHidden(event -> {
            pane.save(true);
            pane.close();
        });
    }
}
