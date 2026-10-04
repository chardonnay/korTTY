package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.KorttyClipboard;
import de.kortty.shellintegration.ShellIntegrationSnippet;
import java.lang.ref.WeakReference;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <i>Set Up Shell Integration…</i>: the snippets korTTY ships for bash, zsh and fish, one tab each,
 * read-only, with where the snippet goes and a <b>Copy</b> button. Opened from the right-click menu
 * of a pane whose shell sends no prompt marks, and from <i>Settings → Terminal → Shell integration</i>.
 *
 * <ul>
 *   <li>Non-modal, so the snippet can be pasted into a terminal while the window stays open. One
 *       window at a time: opening it again for the same owner brings it to the front, opening it for
 *       another window moves it there.</li>
 *   <li><b>Copy</b> and the text's own Copy go through {@link KorttyClipboard}, so the enterprise
 *       policy's internal clipboard mode keeps the snippet inside korTTY; the status line then says
 *       so. The keyboard shortcut is covered by {@code PolicyClipboardGuard}.</li>
 *   <li>The text is the shipped resource ({@link ShellIntegrationSnippet}), which the guide page
 *       prints too; {@code ShellIntegrationSnippetsTest} pins that both are the same.</li>
 *   <li>Nothing is sent to a terminal or written to a server: the user puts the snippet where it
 *       belongs.</li>
 * </ul>
 */
public final class ShellIntegrationSetupDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(ShellIntegrationSetupDialog.class);

    /** Marks the dialog pane, so a smoke test can find the window among the open ones. */
    public static final String STYLE_CLASS = "shell-integration-setup-dialog";

    /** The guide section on setting shell integration up, which the window's manual link opens. */
    static final String GUIDE_LOCATION = "features/shell-integration.html#setting-it-up";

    private static final double WIDTH = 880;

    private static final int SNIPPET_ROWS = 18;

    /** The open window, if any; at most one exists. FX thread only. */
    private static WeakReference<ShellIntegrationSetupDialog> openDialog = new WeakReference<>(null);

    /** The shell whose tab was selected last, opened again when the caller names none. Session only. */
    private static ShellIntegrationSnippet lastShell = ShellIntegrationSnippet.BASH;

    private final @Nullable Window owner;

    private final TabPane tabs = new TabPane();

    private final Label copyStatus = new Label();

    /**
     * Opens the window, or brings the open one to the front. FX thread only.
     *
     * @param owner the window it belongs to; null for none
     * @param shell the shell whose tab to select, or null for the one selected last
     */
    public static void open(@Nullable Window owner, @Nullable ShellIntegrationSnippet shell) {
        ShellIntegrationSetupDialog open = openDialog.get();
        if (open != null && open.isShowing()) {
            if (open.owner == owner) {
                if (shell != null) {
                    open.select(shell);
                }
                open.revealDialogOrHost();
                return;
            }
            open.close();
        }
        ShellIntegrationSetupDialog dialog = new ShellIntegrationSetupDialog(owner, shell != null ? shell : lastShell);
        openDialog = new WeakReference<>(dialog);
        dialog.show();
    }

    /** Builds the window without showing it; {@link #open(Window, ShellIntegrationSnippet)} opens it. */
    ShellIntegrationSetupDialog(@Nullable Window owner, @NotNull ShellIntegrationSnippet selected) {
        this.owner = owner;
        getDialogPane().getStyleClass().add(STYLE_CLASS);
        setTitle(I18n.get("terminal.shellIntegration.setup.title"));
        setHeaderText(I18n.get("terminal.shellIntegration.setup.header"));
        setResizable(true);
        // A Dialog with an owner defaults to APPLICATION_MODAL, which would block the terminal the
        // snippet is to be pasted into.
        initModality(Modality.NONE);
        if (owner != null) {
            initOwner(owner);
        }

        double width = UiFontScaleSupport.scaleDimension(WIDTH, true);
        double textWidth = width - UiFontScaleSupport.scaleDimension(40, true);

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            copyStatus.setText("");
            if (now != null && now.getUserData() instanceof ShellIntegrationSnippet snippet) {
                lastShell = snippet;
            }
        });
        for (ShellIntegrationSnippet snippet : ShellIntegrationSnippet.values()) {
            tabs.getTabs().add(snippetTab(snippet, textWidth));
        }
        VBox.setVgrow(tabs, Priority.ALWAYS);
        select(selected);

        Button copy = new Button(I18n.get("terminal.shellIntegration.setup.copy"));
        copy.setDefaultButton(true);
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setOnAction(event -> copySelectedSnippet());
        copyStatus.setWrapText(true);
        copyStatus.setStyle(MutedTextStyle.MUTED);
        HBox copyRow = new HBox(10, copy, copyStatus);
        copyRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copyStatus, Priority.ALWAYS);

        Hyperlink manual = new Hyperlink(I18n.get("terminal.shellIntegration.setup.manual"));
        manual.setOnAction(event -> openManual());

        VBox body = new VBox(10,
            wrapped(I18n.get("terminal.shellIntegration.setup.intro"), textWidth),
            tabs,
            copyRow,
            hint(I18n.get("terminal.shellIntegration.setup.check"), textWidth),
            manual);
        getDialogPane().setContent(body);
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(width);
        setResultConverter(button -> null);
        setOnShown(event -> copy.requestFocus());
    }

    /** The snippet of the selected tab. */
    private @NotNull ShellIntegrationSnippet selectedSnippet() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab != null && tab.getUserData() instanceof ShellIntegrationSnippet snippet
            ? snippet : ShellIntegrationSnippet.BASH;
    }

    private void select(@NotNull ShellIntegrationSnippet snippet) {
        for (Tab tab : tabs.getTabs()) {
            if (tab.getUserData() == snippet) {
                tabs.getSelectionModel().select(tab);
                return;
            }
        }
    }

    private Tab snippetTab(ShellIntegrationSnippet snippet, double textWidth) {
        Label instructions = wrapped(I18n.get(snippet.instructionsKey()), textWidth);
        TextArea text = new TextArea(snippetText(snippet));
        text.setEditable(false);
        text.setWrapText(false);
        text.setPrefRowCount(SNIPPET_ROWS);
        text.setStyle("-fx-font-family: 'Monospaced';");
        instructions.setLabelFor(text);
        // Replacing the menu removes the text area's built-in Copy, which always writes to the
        // system clipboard; this one follows the clipboard policy.
        MenuItem copySelection = new MenuItem(I18n.get("editor.context.copy"));
        copySelection.disableProperty().bind(text.selectedTextProperty().isEmpty());
        copySelection.setOnAction(event -> KorttyClipboard.copySelection(text));
        MenuItem selectAll = new MenuItem(I18n.get("editor.context.selectAll"));
        selectAll.setOnAction(event -> text.selectAll());
        text.setContextMenu(new ContextMenu(copySelection, new SeparatorMenuItem(), selectAll));
        VBox.setVgrow(text, Priority.ALWAYS);

        VBox content = new VBox(8, instructions, text);
        Tab tab = new Tab(snippet.shellName(), content);
        tab.setUserData(snippet);
        return tab;
    }

    /** The shipped snippet; a broken build shows the reason instead of failing to open. */
    private static String snippetText(ShellIntegrationSnippet snippet) {
        try {
            return snippet.text();
        } catch (RuntimeException e) {
            logger.warn("Could not read the {} shell-integration snippet", snippet.shellName(), e);
            return "# " + e.getMessage() + "\n";
        }
    }

    private void copySelectedSnippet() {
        ShellIntegrationSnippet snippet = selectedSnippet();
        String text;
        try {
            text = snippet.text();
        } catch (RuntimeException e) {
            logger.warn("Could not read the {} shell-integration snippet", snippet.shellName(), e);
            return;
        }
        KorttyClipboard.setText(text);
        copyStatus.setText(I18n.get(KorttyClipboard.isInternalMode()
            ? "terminal.shellIntegration.setup.copiedInternal"
            : "terminal.shellIntegration.setup.copied"));
    }

    private void openManual() {
        try {
            GuideViewer.show(KorTTYApplication.getInstance(), owner, GUIDE_LOCATION);
        } catch (RuntimeException e) {
            logger.warn("Could not open the shell integration guide", e);
        }
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
