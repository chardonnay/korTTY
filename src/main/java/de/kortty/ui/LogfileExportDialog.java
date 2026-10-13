package de.kortty.ui;

import de.kortty.core.LogTimeRange;
import de.kortty.core.TerminalLogExportOptions;
import de.kortty.core.SessionJournalExportProtection;
import de.kortty.model.TerminalLogConfig.LogFormat;
import de.kortty.model.GPGKey;
import javafx.scene.control.PasswordField;
import de.kortty.core.SessionJournalExportFilter;
import de.kortty.core.TerminalLogFile;
import de.kortty.core.TerminalLogNaming;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Asks how the chosen logs are exported: the compression of the copies, and optionally a time range
 * the copies are cut down to.
 *
 * <p>A cut copy holds only the entries inside the range, so it is not a byte copy and gets a name
 * the retention sweep does not own. A whole-file copy, by contrast, may be written into a log folder
 * under a name the sweep would delete later; the manager checks that after this dialog returns, so
 * this dialog stays a pure question and never touches the disk.</p>
 *
 * <p>The range fields are prefilled from the list's current time filter when there is one, and from
 * the earliest start to now otherwise, so the user only has to narrow them down. A range whose start
 * is after its end can never match anything, so Export stays disabled until it is fixed.</p>
 */
public final class LogfileExportDialog extends ThemeAwareDialog<LogfileExportDialog.ExportChoice> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final double CONTENT_WIDTH = 720;
    private static final double CONTENT_HEIGHT = 440;

    /**
     * The answer: the compression of the copies, and the range to cut them to, or {@code null} for
     * whole-file copies. An open range (no bounds at all) cuts nothing and is reported as {@code null}
     * too, so the caller treats it exactly like a plain copy.
     */
    public record ExportChoice(TerminalLogExportOptions options, LogTimeRange range) {
    }

    private final ComboBox<TerminalLogExportOptions.Archive> formatCombo = new ComboBox<>();
    private final ComboBox<String> contentCombo = new ComboBox<>();
    private final CheckBox passwordCheck = new CheckBox(I18n.get("logfile.export.password"));
    private final PasswordField password = new PasswordField();
    private final PasswordField confirmation = new PasswordField();
    private final CheckBox gpgCheck = new CheckBox(I18n.get("logfile.export.gpg"));
    private final ComboBox<GPGKey> gpgKeys = new ComboBox<>();
    private final CheckBox rangeCheck = new CheckBox(I18n.get("logfile.export.range"));
    private final DatePicker fromDate = new DatePicker();
    private final TextField fromTime = new TextField();
    private final DatePicker toDate = new DatePicker();
    private final TextField toTime = new TextField();
    private final Label rangeInvalid = new Label(I18n.get("logfile.export.rangeInvalid"));

    /**
     * @param owner  the window to centre on, or {@code null} for none
     * @param files  the logs being exported, used for the count and as the default time span
     * @param preset the time filter of the list, or {@code null}; selects the range checkbox when set
     */
    public LogfileExportDialog(Window owner, List<TerminalLogFile> files, LogTimeRange preset) {
        if (owner != null) {
            initOwner(owner);
        }
        setTitle(I18n.get("logfile.export.title"));
        setHeaderText(I18n.get("logfile.export.header", files.size()));
        setResizable(true);

        formatCombo.getItems().setAll(TerminalLogExportOptions.Archive.values());
        formatCombo.setValue(TerminalLogExportOptions.Archive.ZSTD);
        formatCombo.setConverter(new StringConverter<>() {
            @Override public String toString(TerminalLogExportOptions.Archive value) {
                return value == null ? "" : value == TerminalLogExportOptions.Archive.ZIP ? "ZIP"
                    : LogfileManagerDialog.compressionLabel(value.compression());
            }
            @Override public TerminalLogExportOptions.Archive fromString(String text) { return null; }
        });
        contentCombo.getItems().add(I18n.get("logfile.export.unchanged"));
        for (LogFormat value : LogFormat.values()) { contentCombo.getItems().add(value.getDisplayName()); }
        contentCombo.getSelectionModel().selectFirst();
        passwordCheck.disableProperty().bind(formatCombo.valueProperty().isNotEqualTo(TerminalLogExportOptions.Archive.ZIP));
        password.disableProperty().bind(passwordCheck.selectedProperty().not().or(passwordCheck.disabledProperty()));
        confirmation.disableProperty().bind(password.disableProperty());
        confirmation.setPromptText(I18n.get("logfile.export.confirmPassword"));
        var app = de.kortty.KorTTYApplication.getInstance();
        if (app != null && app.getGpgKeyManager() != null) { gpgKeys.getItems().setAll(app.getGpgKeyManager().getAllKeys()); }
        if (!gpgKeys.getItems().isEmpty()) { gpgKeys.getSelectionModel().selectFirst(); }
        gpgCheck.setDisable(gpgKeys.getItems().isEmpty());
        gpgKeys.disableProperty().bind(gpgCheck.selectedProperty().not());

        rangeCheck.setSelected(preset != null && !preset.isOpen());
        prefillRange(files, preset);
        fromDate.setPrefWidth(140);
        toDate.setPrefWidth(140);
        fromTime.setPrefWidth(62);
        toTime.setPrefWidth(62);
        fromTime.setPromptText(I18n.get("logfile.manager.filter.time.prompt"));
        toTime.setPromptText(I18n.get("logfile.manager.filter.time.prompt"));
        fromDate.disableProperty().bind(rangeCheck.selectedProperty().not());
        fromTime.disableProperty().bind(rangeCheck.selectedProperty().not());
        toDate.disableProperty().bind(rangeCheck.selectedProperty().not());
        toTime.disableProperty().bind(rangeCheck.selectedProperty().not());

        BooleanBinding invalid = Bindings.createBooleanBinding(
            () -> rangeCheck.isSelected()
                && isRangeInvalid(fromDate.getValue(), fromTime.getText(), toDate.getValue(), toTime.getText()),
            rangeCheck.selectedProperty(), fromDate.valueProperty(), fromTime.textProperty(),
            toDate.valueProperty(), toTime.textProperty());
        rangeInvalid.setWrapText(true);
        // A literal red: the Normal design has no AtlantaFX colour tokens (same tone as the journal badges).
        rangeInvalid.setStyle("-fx-text-fill: rgb(239, 68, 68);");
        rangeInvalid.visibleProperty().bind(invalid);
        rangeInvalid.managedProperty().bind(invalid);

        Label cutHint = new Label(I18n.get("logfile.export.cutHint"));
        cutHint.setWrapText(true);
        cutHint.setMaxWidth(440);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.add(new Label(I18n.get("logfile.export.format")), 0, 0);
        grid.add(formatCombo, 1, 0);
        grid.add(rangeCheck, 1, 1);
        grid.add(new Label(I18n.get("logfile.export.rangeFrom")), 0, 2);
        grid.add(new HBox(8, fromDate, fromTime), 1, 2);
        grid.add(new Label(I18n.get("logfile.export.rangeTo")), 0, 3);
        grid.add(new HBox(8, toDate, toTime), 1, 3);
        grid.add(cutHint, 1, 4);
        grid.add(rangeInvalid, 1, 5);
        grid.add(new Label(I18n.get("logfile.export.contentFormat")), 0, 6);
        grid.add(contentCombo, 1, 6);
        grid.add(passwordCheck, 0, 7);
        grid.add(password, 1, 7);
        grid.add(confirmation, 1, 8);
        grid.add(gpgCheck, 0, 9);
        grid.add(gpgKeys, 1, 9);
        if (gpgKeys.getItems().isEmpty()) { grid.add(new Label(I18n.get("export.noGPGKeys")), 1, 10); }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(CONTENT_WIDTH);
        scroll.setPrefViewportHeight(CONTENT_HEIGHT);
        // A restored small window must shrink the viewport, never push the button bar off-screen.
        scroll.setMinSize(0, 0);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        getDialogPane().setContent(scroll);

        ButtonType exportType = new ButtonType(I18n.get("logfile.manager.export"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(exportType, cancelType);
        Button export = (Button) getDialogPane().lookupButton(exportType);
        BooleanBinding invalidProtection = Bindings.createBooleanBinding(() ->
            (formatCombo.getValue() == TerminalLogExportOptions.Archive.ZIP && passwordCheck.isSelected()
                && (password.getText().isEmpty() || !password.getText().equals(confirmation.getText())))
            || (gpgCheck.isSelected() && gpgKeys.getValue() == null),
            formatCombo.valueProperty(), passwordCheck.selectedProperty(), password.textProperty(),
            confirmation.textProperty(), gpgCheck.selectedProperty(), gpgKeys.valueProperty());
        export.disableProperty().bind(invalid.or(invalidProtection));
        setOnHidden(event -> { password.clear(); confirmation.clear(); });
        setResultConverter(button -> button == exportType ? choice() : null);
    }

    /**
     * Fills the range fields: the preset when it has bounds, otherwise the span from the earliest
     * start among the files to now. The preset's exclusive end is shown as the last minute inside it,
     * which is what the user typed; reading it back gives the same exclusive end again.
     */
    private void prefillRange(List<TerminalLogFile> files, LogTimeRange preset) {
        LocalDateTime earliest = files.stream()
            .map(TerminalLogFile::startedAt)
            .min(Comparator.naturalOrder())
            .orElseGet(LocalDateTime::now);
        LocalDateTime from = preset != null && preset.from() != null ? preset.from() : earliest;
        LocalDateTime to = preset != null && preset.toExclusive() != null
            ? preset.toExclusive().minusMinutes(1)
            : LocalDateTime.now();
        fromDate.setValue(from.toLocalDate());
        fromTime.setText(TIME.format(from));
        toDate.setValue(to.toLocalDate());
        toTime.setText(TIME.format(to));
    }

    private ExportChoice choice() {
        LogTimeRange range = rangeCheck.isSelected()
            ? rangeOf(fromDate.getValue(), fromTime.getText(), toDate.getValue(), toTime.getText())
            : null;
        int content = contentCombo.getSelectionModel().getSelectedIndex();
        GPGKey key = gpgCheck.isSelected() ? gpgKeys.getValue() : null;
        var protection = key == null ? SessionJournalExportProtection.Protection.NONE
            : SessionJournalExportProtection.Protection.gpg(
                key.getFingerprint() != null && !key.getFingerprint().isBlank() ? key.getFingerprint() : key.getKeyId(),
                key.getPublicKeyPath());
        char[] secret = passwordCheck.isSelected() && formatCombo.getValue() == TerminalLogExportOptions.Archive.ZIP
            ? password.getText().toCharArray() : null;
        try {
            return new ExportChoice(new TerminalLogExportOptions(content == 0 ? null : LogFormat.values()[content - 1],
                formatCombo.getValue(), secret, protection), range);
        } finally { if (secret != null) { java.util.Arrays.fill(secret, '\0'); } }
    }

    /**
     * The range the fields describe, or {@code null} when it is open. A non-blank time that does not
     * parse makes the whole range unusable, so the caller must have checked {@link #isRangeInvalid}.
     */
    static LogTimeRange rangeOf(LocalDate fromDate, String fromTime, LocalDate toDate, String toTime) {
        LogTimeRange range = LogTimeRange.of(fromDate, parse(fromTime), toDate, parse(toTime));
        return range.isOpen() ? null : range;
    }

    /**
     * Whether the range fields cannot be used: a non-blank time does not parse, or the start is not
     * before the end. Blank fields are fine, they mean the start or end of the span.
     */
    static boolean isRangeInvalid(LocalDate fromDate, String fromTime, LocalDate toDate, String toTime) {
        if (broken(fromTime) || broken(toTime)) {
            return true;
        }
        return LogTimeRange.of(fromDate, parse(fromTime), toDate, parse(toTime)).isInvalid();
    }

    private static boolean broken(String text) {
        return text != null && !text.isBlank() && parse(text) == null;
    }

    private static LocalTime parse(String text) {
        return SessionJournalExportFilter.parseTimeOfDay(text);
    }
}
