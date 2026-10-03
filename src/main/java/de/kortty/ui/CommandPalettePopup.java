package de.kortty.ui;

import de.kortty.ui.actions.CommandPaletteModel;
import de.kortty.ui.actions.MruList;
import de.kortty.ui.actions.PaletteEntry;
import de.kortty.ui.actions.PaletteEntry.Kind;
import de.kortty.ui.actions.PaletteSource;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The command palette of a main window (Cmd/Ctrl+Shift+P, View › Command Palette…): a search field
 * over the window's commands, built on {@link QuickPickPopup}. What it lists for a query comes from
 * {@link CommandPaletteModel}.
 *
 * <p>Each row shows a kind badge, the title, a dimmed detail (for a menu command its menu path), a
 * check mark for a toggle that is on, and the shortcut. A row that cannot run right now is greyed
 * out; choosing it keeps the palette open and the footer says why. Otherwise the footer shows the
 * keys. Enter closes the palette first and runs the row afterwards, so a dialog the row opens, or the
 * nested event loop of a progress dialog, never runs inside the popup's key handler. A row with an
 * {@link PaletteEntry#alternate() alternate} (a snippet: open it in the Snippet Manager instead of
 * running it) does that on Alt+Enter, Option+Enter on macOS, the same way, also while it cannot run;
 * while such a row is selected the footer names that key.
 *
 * <p>Every key typed into the palette stays there ({@link QuickPickKeyFirewall}); only the chord's
 * KEY_PRESSED passes, so the window's scene shortcut router closes the palette and swallows the
 * chord's KEY_TYPED. The recent choices are kept for the session, in memory only, and shared by all
 * windows.
 */
final class CommandPalettePopup {

    static final String ROOT_ID = "command-palette";
    static final String FIELD_ID = "command-palette-field";
    static final String LIST_ID = "command-palette-list";
    static final String FOOTER_ID = "command-palette-footer";
    /** Style class of the footer while it says why the chosen row cannot run. */
    static final String REASON_STYLE_CLASS = "palette-footer-reason";
    static final double WIDTH = 640;

    /** The keys a row's labels come from; pinned by CommandPaletteI18nCoverageTest. */
    static final List<String> KEYS = List.of(
        "palette.prompt", "palette.empty", "palette.disabled", "palette.hint", "palette.scopes",
        "palette.checked", "palette.kind.action", "palette.kind.tab", "palette.kind.connection",
        "palette.kind.snippet", "palette.hint.snippet");

    /** The keys of the recent choices, for every window of this session. FX thread only, never saved. */
    private static final MruList<String> RECENT = new MruList<>(MruList.DEFAULT_CAPACITY);
    /** The key of a row's {@link PaletteEntry#alternate() alternate}; {@link QuickPickPopup} answers it. */
    private static final KeyCombination ALTERNATE_CHORD = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.ALT_DOWN);
    /** The room a row leaves for the cell's padding, so the shortcut column ends inside the list. */
    private static final double ROW_INSET = 20;
    /**
     * The largest share of a row its title may take. A title can come from someone else: a teamwork
     * connection's name from a shared file, a tab's from the program in the terminal. However long
     * it is, the detail next to it, which names where the row really connects, keeps the rest.
     */
    static final double TITLE_SHARE = 0.5;

    private final CommandPaletteModel model;
    private final QuickPickPopup<PaletteEntry> picker;
    private final Label footer = new Label();
    private final String hint;
    private final String alternateHint;

    /**
     * @param sources     the row sources, in the order their rows are listed
     * @param passThrough the key events that may reach the window behind, see {@link PaletteKeys#passThrough}
     */
    CommandPalettePopup(List<? extends PaletteSource> sources, Predicate<KeyEvent> passThrough) {
        this.model = new CommandPaletteModel(sources, RECENT);
        this.hint = hintText(model.kinds());
        this.alternateHint = alternateHintText(model.kinds(), alternateChordText());
        footer.setId(FOOTER_ID);
        footer.getStyleClass().add("palette-footer");
        footer.setWrapText(true);
        footer.setMaxWidth(Double.MAX_VALUE);
        this.picker = QuickPickPopup.<PaletteEntry>builder(ROOT_ID, FIELD_ID, LIST_ID)
            .prompt(I18n.get("palette.prompt"))
            .empty(I18n.get("palette.empty"))
            .search(model::query)
            .text(PaletteEntry::title)
            .accessibleText(CommandPalettePopup::accessibleText)
            .renderer(CommandPalettePopup::render)
            .choosable(PaletteEntry::enabled)
            .onUnchoosable(this::showReason)
            .onChosen(this::run)
            .alternate(entry -> entry.alternate() != null, this::runAlternate)
            .passThrough(passThrough)
            .width(WIDTH)
            .footer(footer)
            // A hidden palette keeps no rows: they point to tabs, terminals and snippets.
            .onHidden(model::close)
            .build();
        picker.field().textProperty().addListener((obs, was, now) -> showHint());
        picker.list().getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> showHint());
    }

    /** Reads the sources afresh and shows the palette centred at the top of {@code anchor}, empty. */
    void show(Node anchor) {
        model.open();
        showHint();
        picker.show(anchor);
    }

    void hide() {
        picker.hide();
    }

    boolean isShowing() {
        return picker.isShowing();
    }

    TextField field() {
        return picker.field();
    }

    ListView<PaletteEntry> list() {
        return picker.list();
    }

    Label footer() {
        return footer;
    }

    /** Runs the selected (or first) row, as Enter does. */
    void choose() {
        picker.choose();
    }

    private void run(PaletteEntry entry) {
        model.chosen(entry);
        // After the key event that chose it, with the popup already closed.
        Platform.runLater(entry.run());
    }

    /** Alt/Option+Enter on a row with an alternate: as {@link #run}, with the alternate. */
    private void runAlternate(PaletteEntry entry) {
        model.chosen(entry);
        Platform.runLater(entry.alternate());
    }

    private void showReason(PaletteEntry entry) {
        footer.setText(entry.disabledReason().isEmpty() ? I18n.get("palette.disabled") : entry.disabledReason());
        if (!footer.getStyleClass().contains(REASON_STYLE_CLASS)) {
            footer.getStyleClass().add(REASON_STYLE_CLASS);
        }
    }

    /** The keys; the ones of a row with an alternate while such a row is selected. */
    private void showHint() {
        PaletteEntry selected = picker.list().getSelectionModel().getSelectedItem();
        footer.setText(selected != null && selected.alternate() != null ? alternateHint : hint);
        footer.getStyleClass().remove(REASON_STYLE_CLASS);
    }

    /** The keys, and with more than one kind of row the scope prefixes too. */
    static String hintText(Set<Kind> kinds) {
        String keys = I18n.get("palette.hint");
        if (kinds.size() < 2) {
            return keys;
        }
        String scopes = kinds.stream()
            .map(kind -> kind.scopePrefix() + " " + kindLabel(kind))
            .collect(Collectors.joining("   "));
        return keys + "\n" + I18n.get("palette.scopes", scopes);
    }

    /** Alt+Enter as the platform shows it (Option+Enter on macOS): the key of a row's alternate. */
    static String alternateChordText() {
        return ALTERNATE_CHORD.getDisplayText();
    }

    /**
     * The footer while a snippet row is selected: Enter runs it, {@code alternateChord} opens it in
     * the Snippet Manager, and the scope prefixes as in {@link #hintText}.
     */
    static String alternateHintText(Set<Kind> kinds, String alternateChord) {
        String hint = hintText(kinds);
        String snippetKeys = I18n.get("palette.hint.snippet", alternateChord);
        int newline = hint.indexOf('\n');
        return newline < 0 ? snippetKeys : snippetKeys + hint.substring(newline);
    }

    static String kindLabel(Kind kind) {
        return switch (kind) {
            case ACTION -> I18n.get("palette.kind.action");
            case TAB -> I18n.get("palette.kind.tab");
            case CONNECTION -> I18n.get("palette.kind.connection");
            case SNIPPET -> I18n.get("palette.kind.snippet");
        };
    }

    /** What a screen reader reads for a row; the popup adds "Disabled" for a row that cannot run. */
    static String accessibleText(PaletteEntry entry) {
        List<String> parts = new ArrayList<>();
        parts.add(entry.title());
        parts.add(kindLabel(entry.kind()));
        if (!entry.detail().isEmpty()) {
            parts.add(entry.detail());
        }
        if (entry.checked()) {
            parts.add(I18n.get("palette.checked"));
        }
        if (!entry.shortcut().isEmpty()) {
            parts.add(entry.shortcut());
        }
        return String.join(", ", parts);
    }

    /** The widest a row's title may be in a row {@code rowWidth} wide: {@link #TITLE_SHARE} of it. */
    static double titleMaxWidth(double rowWidth) {
        return Math.max(0, rowWidth * TITLE_SHARE);
    }

    private static void render(ListCell<PaletteEntry> cell, PaletteEntry entry) {
        Row row = (Row) cell.getProperties().computeIfAbsent(Row.class, key -> new Row(cell));
        row.show(entry);
        cell.setText(null);
        cell.setGraphic(row.root);
    }

    /** The nodes of one row, made once per list cell and reused as the cell shows other entries. */
    private static final class Row {
        private final Label kind = new Label();
        private final Label title = new Label();
        private final Label detail = new Label();
        private final Label check = new Label("✓");
        private final Label shortcut = new Label();
        private final HBox root;

        Row(ListCell<PaletteEntry> cell) {
            kind.getStyleClass().add("palette-kind");
            kind.setMinWidth(Region.USE_PREF_SIZE);
            title.getStyleClass().add("palette-title");
            title.setMinWidth(0);
            detail.getStyleClass().add("palette-detail");
            detail.setMinWidth(0);
            check.getStyleClass().add("palette-check");
            check.setMinWidth(Region.USE_PREF_SIZE);
            shortcut.getStyleClass().add("palette-shortcut");
            shortcut.setMinWidth(Region.USE_PREF_SIZE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            root = new HBox(8, kind, title, detail, spacer, check, shortcut);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setMinWidth(0);
            // The row is as wide as the list, not as its texts: long rows are cut with an ellipsis
            // instead of giving the list a horizontal scroll bar.
            cell.setPrefWidth(0);
            root.prefWidthProperty().bind(cell.widthProperty().subtract(ROW_INSET));
            root.setMaxWidth(Region.USE_PREF_SIZE);
            // A long title is cut at half the row, so it cannot squeeze the detail (user@host) away.
            title.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> titleMaxWidth(root.getPrefWidth()), root.prefWidthProperty()));
        }

        void show(PaletteEntry entry) {
            kind.setText(kindLabel(entry.kind()));
            title.setText(entry.title());
            detail.setText(entry.detail());
            detail.setVisible(!entry.detail().isEmpty());
            detail.setManaged(!entry.detail().isEmpty());
            check.setVisible(entry.checked());
            check.setManaged(entry.checked());
            shortcut.setText(entry.shortcut());
            shortcut.setVisible(!entry.shortcut().isEmpty());
            shortcut.setManaged(!entry.shortcut().isEmpty());
        }
    }
}
