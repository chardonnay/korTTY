package de.kortty.ui;

import de.kortty.ui.TerminalClipboardAccess.ReadDecision;
import de.kortty.ui.TerminalClipboardAccess.ReadPromptRequest;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Asks whether a program in a terminal tab may read the clipboard ({@code OSC 5522}): it names the tab, the
 * program as it calls itself and the MIME types it asks for. Deny is the default button and what closing the
 * dialog means; "Allow for this program" is offered only when the program sent a name and a password, which
 * is how it is recognised again. JavaFX thread.
 */
final class ClipboardReadPromptDialog {

    static final String STYLE_CLASS = "clipboard-read-prompt-dialog";

    private static final double WIDTH = 600;

    private final Supplier<Window> owner;

    /** @param owner the window the dialog belongs to, read when it opens; may return null */
    ClipboardReadPromptDialog(Supplier<Window> owner) {
        this.owner = owner != null ? owner : () -> null;
    }

    /** Shows the prompt; {@code answer} gets the decision when it closes. */
    void ask(ReadPromptRequest request, Consumer<ReadDecision> answer) {
        build(request, answer).show();
    }

    /** The prompt's buttons, in the order they are added; Deny first. */
    static List<ReadDecision> decisions(ReadPromptRequest request) {
        List<ReadDecision> decisions = new ArrayList<>();
        decisions.add(ReadDecision.DENY);
        if (request.canRemember()) {
            decisions.add(ReadDecision.ALLOW_FOR_PROGRAM);
        }
        decisions.add(ReadDecision.ALLOW_ONCE);
        return decisions;
    }

    /** The prompt's header: the program by its name, or as "a program", and the tab. */
    static String header(ReadPromptRequest request) {
        // The tab's and the program's names, which a program sets, are the last placeholders.
        return request.programName() != null
            ? I18n.get("terminal.osc5522.prompt.headerNamed", request.tabName(), request.programName())
            : I18n.get("terminal.osc5522.prompt.header", request.tabName());
    }

    /** The list of requested types, with a note for the ones left out. */
    static String types(ReadPromptRequest request) {
        String list = String.join(", ", request.mimeTypes());
        return request.moreTypes() > 0
            ? I18n.get("terminal.osc5522.prompt.typesMore", request.moreTypes(), list)
            : I18n.get("terminal.osc5522.prompt.types", list);
    }

    Alert build(ReadPromptRequest request, Consumer<ReadDecision> answer) {
        List<ButtonType> buttons = new ArrayList<>();
        List<ReadDecision> decisions = decisions(request);
        for (ReadDecision decision : decisions) {
            buttons.add(switch (decision) {
                case DENY -> new ButtonType(I18n.get("terminal.osc5522.prompt.deny"), ButtonBar.ButtonData.CANCEL_CLOSE);
                case ALLOW_FOR_PROGRAM -> new ButtonType(I18n.get("terminal.osc5522.prompt.allowProgram"),
                    ButtonBar.ButtonData.OTHER);
                case ALLOW_ONCE -> new ButtonType(I18n.get("terminal.osc5522.prompt.allowOnce"),
                    ButtonBar.ButtonData.OK_DONE);
            });
        }
        Alert alert = new Alert(Alert.AlertType.WARNING, "", buttons.toArray(ButtonType[]::new));
        alert.getDialogPane().getStyleClass().add(STYLE_CLASS);
        DialogThemeHelper.applyTheme(alert);
        Window ownerWindow = owner.get();
        if (ownerWindow != null) {
            alert.initOwner(ownerWindow);
            alert.initModality(Modality.WINDOW_MODAL);
        }
        alert.setTitle(I18n.get("terminal.osc5522.prompt.title"));
        alert.setHeaderText(header(request));

        ButtonType denyType = buttons.get(0);
        Button deny = (Button) alert.getDialogPane().lookupButton(denyType);
        for (ButtonType type : buttons) {
            Button button = (Button) alert.getDialogPane().lookupButton(type);
            button.setDefaultButton(type == denyType);
            // Every label in full, also in the longer translations.
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        deny.setCancelButton(true);

        double textWidth = UiFontScaleSupport.scaleDimension(WIDTH - 40, true);
        VBox body = new VBox(8, wrapped(types(request), textWidth),
            hint(I18n.get("terminal.osc5522.prompt.hint"), textWidth));
        alert.getDialogPane().setContent(body);
        alert.getDialogPane().setPrefWidth(UiFontScaleSupport.scaleDimension(WIDTH, true));
        alert.setOnShown(event -> deny.requestFocus());
        alert.setOnHidden(event -> {
            ButtonType result = alert.getResult();
            int index = result != null ? buttons.indexOf(result) : -1;
            answer.accept(index >= 0 ? decisions.get(index) : ReadDecision.DENY);
        });
        return alert;
    }

    private static Label wrapped(String text, double width) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(width);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label hint(String text, double width) {
        Label label = wrapped(text, width);
        label.setStyle(MutedTextStyle.MUTED);
        return label;
    }
}
