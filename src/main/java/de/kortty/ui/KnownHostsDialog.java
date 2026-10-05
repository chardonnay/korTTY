package de.kortty.ui;

import de.kortty.core.OpenSshKnownHostsParser;
import de.kortty.core.SshHostKeyTrustManager;
import de.kortty.core.SshHostKeyTrustManager.KnownHostsConflict;
import de.kortty.core.SshHostKeyTrustManager.KnownHostsImportResult;
import de.kortty.core.SshHostKeyTrustManager.TrustedHostKey;
import de.kortty.policy.PolicyRestrictionException;
import de.kortty.policy.PolicyUiSupport;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lists the trusted SSH host keys of interactive connections (the TOFU store of
 * {@link SshHostKeyTrustManager}) with a search field and a confirmed "Remove…".
 *
 * <p>Removal is compare-and-delete: it only deletes the pin while it still has the fingerprint shown
 * in the confirmation, so a key changed meanwhile in another window is never removed by accident.
 * While the enterprise policy enforces host-key checking, removal is disabled. A store that cannot
 * be read shows the error and offers no edit actions, so it is never mistaken for an empty one.</p>
 *
 * <p>"Import from known_hosts…" reads an OpenSSH {@code known_hosts} file off the FX thread, shows
 * what an import would add (a dry run) and asks before trusting any new key; afterwards it shows a
 * summary of added keys, keys already trusted, conflicts (never overwritten) and skipped lines.</p>
 */
public class KnownHostsDialog extends ThemeAwareDialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(KnownHostsDialog.class);

    private final SshHostKeyTrustManager trustManager;
    private final TextField searchField = new TextField();
    private final TableView<TrustedHostKey> table = new TableView<>();
    private final Button removeButton = new Button();
    private final Button importButton = new Button();
    private final Label policyLabel = new Label();
    private List<TrustedHostKey> loadedKeys = List.of();
    private boolean loadFailed;
    private boolean importRunning;

    public KnownHostsDialog() {
        this(SshHostKeyTrustManager.shared());
    }

    public KnownHostsDialog(SshHostKeyTrustManager trustManager) {
        this.trustManager = java.util.Objects.requireNonNull(trustManager, "trustManager");

        setTitle(I18n.get("ssh.knownHosts.title"));
        setHeaderText(null);
        setResizable(true);

        searchField.setPromptText(I18n.get("ssh.knownHosts.search.prompt"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyFilter());

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.getColumns().add(column("ssh.knownHosts.column.host", 190, TrustedHostKey::host));
        table.getColumns().add(column("ssh.knownHosts.column.port", 60, key -> Integer.toString(key.port())));
        table.getColumns().add(column("ssh.knownHosts.column.algorithm", 130, TrustedHostKey::algorithm));
        TableColumn<TrustedHostKey, String> fingerprint =
            column("ssh.knownHosts.column.fingerprint", 460, TrustedHostKey::fingerprintSha256);
        fingerprint.setCellFactory(ignored -> new FingerprintCell());
        table.getColumns().add(fingerprint);
        table.getColumns().add(column("ssh.knownHosts.column.trustedAt", 160,
            key -> formatTrustedAt(key.trustedAt())));
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> updateActions());

        removeButton.setText(I18n.get("ssh.knownHosts.remove"));
        removeButton.setOnAction(event -> removeSelected());
        // "known_hosts" is a file name: without this the underscore would become a mnemonic marker.
        importButton.setMnemonicParsing(false);
        importButton.setText(I18n.get("ssh.knownHosts.import"));
        importButton.setOnAction(event -> chooseAndImport());

        policyLabel.setWrapText(true);
        policyLabel.setStyle("-fx-font-size: 0.8462em;");

        HBox actions = new HBox(10, removeButton, importButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox layout = new VBox(10, searchField, table, actions, policyLabel);
        layout.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        getDialogPane().setContent(layout);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(1060);
        getDialogPane().setPrefHeight(480);

        reload();
    }

    /**
     * The keys whose host, {@code host:port}, port, algorithm or SHA-256 fingerprint contain
     * {@code query}, ignoring case; a blank query returns every key in its original order.
     */
    static List<TrustedHostKey> filter(List<TrustedHostKey> keys, String query) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.copyOf(keys);
        }
        List<TrustedHostKey> matches = new ArrayList<>();
        for (TrustedHostKey key : keys) {
            if (key != null && matches(key, needle)) {
                matches.add(key);
            }
        }
        return List.copyOf(matches);
    }

    private static boolean matches(TrustedHostKey key, String needle) {
        return contains(key.host() + ":" + key.port(), needle)
            || contains(key.fingerprintSha256(), needle)
            || contains(key.algorithm(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static TableColumn<TrustedHostKey, String> column(
            String titleKey, double prefWidth, Function<TrustedHostKey, String> value) {
        TableColumn<TrustedHostKey, String> column = new TableColumn<>(I18n.get(titleKey));
        column.setPrefWidth(prefWidth);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
            cell.getValue() != null ? value.apply(cell.getValue()) : ""));
        return column;
    }

    /** Monospace fingerprint, with the full value as tooltip when the column is narrower. */
    private static final class FingerprintCell extends TableCell<TrustedHostKey, String> {
        private FingerprintCell() {
            setStyle("-fx-font-family: monospace;");
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            boolean blank = empty || item == null || item.isBlank();
            setText(blank ? null : item);
            setTooltip(blank ? null : new Tooltip(item));
        }
    }

    private static String formatTrustedAt(String trustedAt) {
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(trustedAt));
        } catch (RuntimeException e) {
            return trustedAt != null ? trustedAt : "";
        }
    }

    private void reload() {
        TrustedHostKey previouslySelected = table.getSelectionModel().getSelectedItem();
        try {
            loadedKeys = trustManager.listTrustedKeys();
            loadFailed = false;
            table.setPlaceholder(new Label(I18n.get("ssh.knownHosts.empty")));
        } catch (Exception e) {
            logger.error("Could not load the trusted SSH host keys", e);
            loadedKeys = List.of();
            loadFailed = true;
            Label failure = new Label(I18n.get("ssh.knownHosts.loadFailed", safeMessage(e)));
            failure.setWrapText(true);
            failure.setPadding(new Insets(10));
            table.setPlaceholder(failure);
        }
        applyFilter();
        if (previouslySelected != null && table.getItems().contains(previouslySelected)) {
            table.getSelectionModel().select(previouslySelected);
        }
        updateActions();
    }

    private void applyFilter() {
        table.getItems().setAll(filter(loadedKeys, searchField.getText()));
    }

    private void updateActions() {
        boolean locked = trustManager.isPinManagementLocked();
        boolean noSelection = table.getSelectionModel().getSelectedItem() == null;
        removeButton.setDisable(loadFailed || locked || noSelection);
        removeButton.setTooltip(locked ? new Tooltip(PolicyUiSupport.managedByOrganizationText()) : null);
        importButton.setDisable(loadFailed || importRunning);
        boolean showPolicy = locked && !loadFailed;
        policyLabel.setText(showPolicy
            ? I18n.get("ssh.knownHosts.policyLocked") + " " + PolicyUiSupport.managedByOrganizationText()
            : "");
        policyLabel.setVisible(showPolicy);
        policyLabel.setManaged(showPolicy);
    }

    private void removeSelected() {
        TrustedHostKey selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || loadFailed || trustManager.isPinManagementLocked()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        initOwner(confirm);
        confirm.setTitle(I18n.get("ssh.knownHosts.remove.confirm.title"));
        confirm.setHeaderText(I18n.get("ssh.knownHosts.remove.confirm.header", selected.host(), selected.port()));
        confirm.setContentText(I18n.get("ssh.knownHosts.remove.confirm.message",
            selected.algorithm(), selected.fingerprintSha256()));
        confirm.getButtonTypes().setAll(ButtonType.NO, ButtonType.YES);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.YES) {
            return;
        }

        try {
            if (trustManager.removePin(selected.host(), selected.port(), selected.fingerprintSha256())) {
                Telemetry.track(TelemetryEvents.SECURITY_ENTRY_CHANGED,
                    Map.of("manager", "known_hosts", "op", "remove", "via", "manual"));
            } else {
                showMessage(Alert.AlertType.WARNING, I18n.get("ssh.knownHosts.remove.stale"));
            }
        } catch (PolicyRestrictionException e) {
            showMessage(Alert.AlertType.WARNING,
                I18n.get("ssh.knownHosts.policyLocked") + " " + PolicyUiSupport.managedByOrganizationText());
        } catch (Exception e) {
            logger.error("Could not remove the trusted SSH host key for {}:{}", selected.host(), selected.port(), e);
            showMessage(Alert.AlertType.ERROR, I18n.get("ssh.knownHosts.remove.failed", safeMessage(e)));
        }
        reload();
    }

    private void chooseAndImport() {
        if (loadFailed || importRunning) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("ssh.knownHosts.import.chooserTitle"));
        Path defaultFile = OpenSshKnownHostsParser.defaultKnownHostsFile();
        Path defaultDirectory = defaultFile.getParent();
        if (defaultDirectory != null && Files.isDirectory(defaultDirectory)) {
            chooser.setInitialDirectory(defaultDirectory.toFile());
        }
        chooser.setInitialFileName(defaultFile.getFileName().toString());
        File chosen = chooser.showOpenDialog(ownerWindow());
        if (chosen == null) {
            return;
        }
        Path file = chosen.toPath();
        runImportStep(() -> trustManager.importKnownHosts(OpenSshKnownHostsParser.read(file), true),
            preview -> confirmImport(file, preview));
    }

    /** Asks before trusting new keys; with nothing new to add it only shows the summary. */
    private void confirmImport(Path file, KnownHostsImportResult preview) {
        String fileName = String.valueOf(file.getFileName());
        if (preview.added().isEmpty()) {
            showImportSummary(Alert.AlertType.INFORMATION,
                I18n.get("ssh.knownHosts.import.nothing.header", fileName), preview);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        initOwner(confirm);
        confirm.setTitle(I18n.get("ssh.knownHosts.title"));
        confirm.setHeaderText(I18n.get("ssh.knownHosts.import.confirm.header", preview.added().size(), fileName));
        confirm.setContentText(I18n.get("ssh.knownHosts.import.confirm.message") + "\n\n"
            + summaryText(preview, trustManager.isPinManagementLocked()));
        setDetails(confirm, detailsText(preview));
        ButtonType importType = new ButtonType(
            I18n.get("ssh.knownHosts.import.confirm.button"), ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(importType, ButtonType.CANCEL);
        ((Button) confirm.getDialogPane().lookupButton(importType)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != importType) {
            return;
        }
        runImportStep(() -> trustManager.importKnownHosts(OpenSshKnownHostsParser.read(file), false), result -> {
            if (!result.added().isEmpty()) {
                Telemetry.track(TelemetryEvents.SECURITY_ENTRY_CHANGED,
                    Map.of("manager", "known_hosts", "op", "import", "via", "known_hosts_file"));
            }
            reload();
            boolean warn = !result.conflicts().isEmpty() || !result.trustedButRevoked().isEmpty()
                || !result.source().malformedLines().isEmpty();
            showImportSummary(warn ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION,
                I18n.get("ssh.knownHosts.import.done.header", result.added().size(), fileName), result);
        });
    }

    /** Reads, parses and imports off the FX thread, then hands the result back on it. */
    private void runImportStep(ImportStep step, java.util.function.Consumer<KnownHostsImportResult> onSuccess) {
        importRunning = true;
        updateActions();
        Thread worker = new Thread(() -> {
            try {
                KnownHostsImportResult result = step.run();
                Platform.runLater(() -> {
                    importRunning = false;
                    updateActions();
                    onSuccess.accept(result);
                });
            } catch (Exception e) {
                logger.warn("Could not import the known_hosts file", e);
                Platform.runLater(() -> {
                    importRunning = false;
                    updateActions();
                    showMessage(Alert.AlertType.ERROR, I18n.get("ssh.knownHosts.import.failed", safeMessage(e)));
                });
            }
        }, "kortty-known-hosts-import");
        worker.setDaemon(true);
        worker.start();
    }

    @FunctionalInterface
    private interface ImportStep {
        KnownHostsImportResult run() throws Exception;
    }

    private void showImportSummary(Alert.AlertType type, String header, KnownHostsImportResult result) {
        Alert alert = new Alert(type);
        initOwner(alert);
        alert.setTitle(I18n.get("ssh.knownHosts.title"));
        alert.setHeaderText(header);
        alert.setContentText(summaryText(result, trustManager.isPinManagementLocked()));
        setDetails(alert, detailsText(result));
        alert.showAndWait();
    }

    private static void setDetails(Alert alert, String details) {
        if (details.isBlank()) {
            return;
        }
        TextArea area = new TextArea(details);
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: monospace;");
        area.setPrefRowCount(10);
        alert.getDialogPane().setExpandableContent(area);
        alert.getDialogPane().setPrefWidth(760);
    }

    /**
     * The counts of an import (or its dry run), one per line: added, already trusted and conflicts
     * always, every skipped kind only when it occurred.
     */
    static String summaryText(KnownHostsImportResult result, boolean pinChangesLocked) {
        OpenSshKnownHostsParser.KnownHostsFile file = result.source();
        List<String> lines = new ArrayList<>();
        lines.add(I18n.get("ssh.knownHosts.import.summary.added", result.added().size()));
        lines.add(I18n.get("ssh.knownHosts.import.summary.alreadyTrusted", result.alreadyTrusted()));
        lines.add(I18n.get("ssh.knownHosts.import.summary.conflicts", result.conflicts().size()));
        addIfPositive(lines, "ssh.knownHosts.import.summary.additionalKeys", result.additionalKeys());
        addIfPositive(lines, "ssh.knownHosts.import.summary.hashed", file.hashed());
        addIfPositive(lines, "ssh.knownHosts.import.summary.revoked", file.revoked());
        addIfPositive(lines, "ssh.knownHosts.import.summary.revokedEntries", result.revokedSkipped());
        addIfPositive(lines, "ssh.knownHosts.import.summary.certAuthority", file.certAuthority());
        addIfPositive(lines, "ssh.knownHosts.import.summary.patterns", file.patterns());
        addIfPositive(lines, "ssh.knownHosts.import.summary.unsupported", file.unsupportedKeyType());
        if (!file.malformedLines().isEmpty()) {
            lines.add(I18n.get("ssh.knownHosts.import.summary.malformed",
                file.malformedLines().size(), lineNumbers(file.malformedLines())));
        }
        StringBuilder text = new StringBuilder(String.join("\n", lines));
        if (!result.trustedButRevoked().isEmpty()) {
            text.append("\n\n").append(I18n.get("ssh.knownHosts.import.summary.trustedRevoked",
                result.trustedButRevoked().size()));
        }
        if (!result.conflicts().isEmpty()) {
            text.append("\n\n").append(I18n.get(pinChangesLocked
                ? "ssh.knownHosts.import.summary.conflictHintLocked"
                : "ssh.knownHosts.import.summary.conflictHint"));
        }
        return text.toString();
    }

    /** One line per conflict and per trusted key the file revokes, for the expandable details. */
    static String detailsText(KnownHostsImportResult result) {
        List<String> lines = new ArrayList<>();
        for (KnownHostsConflict conflict : result.conflicts()) {
            lines.add(I18n.get("ssh.knownHosts.import.detail.conflict",
                conflict.host(), conflict.port(), conflict.trustedAlgorithm(), conflict.trustedFingerprintSha256(),
                conflict.fileAlgorithm(), conflict.fileFingerprintSha256(), conflict.lineNumber()));
        }
        for (TrustedHostKey revoked : result.trustedButRevoked()) {
            lines.add(I18n.get("ssh.knownHosts.import.detail.trustedRevoked",
                revoked.host(), revoked.port(), revoked.algorithm(), revoked.fingerprintSha256()));
        }
        return String.join("\n", lines);
    }

    private static void addIfPositive(List<String> lines, String key, int count) {
        if (count > 0) {
            lines.add(I18n.get(key, count));
        }
    }

    /** The first line numbers, comma-separated, with an ellipsis when there are more. */
    static String lineNumbers(List<Integer> numbers) {
        int shown = Math.min(numbers.size(), 20);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(numbers.get(i));
        }
        if (numbers.size() > shown) {
            text.append(", …");
        }
        return text.toString();
    }

    private Window ownerWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }

    private void showMessage(Alert.AlertType type, String message) {
        Alert alert = new Alert(type);
        initOwner(alert);
        alert.setTitle(I18n.get("ssh.knownHosts.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void initOwner(Alert alert) {
        Window owner = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (owner != null) {
            alert.initOwner(owner);
        }
        DialogThemeHelper.applyTheme(alert);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.trim();
    }
}
