package de.kortty.ui;

import de.kortty.memory.external.ExternalMemoryDiscovery;
import de.kortty.memory.external.ExternalMemoryEntry;
import de.kortty.memory.external.ExternalMemoryService;
import de.kortty.memory.external.ExternalMemoryStatus;
import de.kortty.memory.external.ExternalMemoryTool;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyUiSupport;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The "Weitere Memory-Dateien" part of the Memory tab: the approved reference files of other AI tools, each with its
 * status, a switch to send it, and the steps to find, approve and remove one. Every file read runs on a background
 * thread; the list is rebuilt on the FX thread.
 *
 * <p>When the enterprise policy denies external files, the section shows its banner, keeps the list read-only and reads
 * nothing.</p>
 */
final class ExternalMemorySection extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(ExternalMemorySection.class);
    private static final String CARD_STYLE =
        "-fx-background-color: rgba(128,128,128,0.10); -fx-background-radius: 6; -fx-padding: 8 10 8 10;";
    private static final String BANNER_STYLE =
        "-fx-background-color: rgba(234,179,8,0.14); -fx-background-radius: 6; -fx-padding: 8 10 8 10;";
    private static final String ERROR_STYLE = "-fx-text-fill: #d9534f;";

    private final Window owner;
    private final List<AiMemoryPane.ScopeChoice> scopes;
    private final Supplier<AiMemoryPane.ScopeChoice> currentScope;
    private final ExternalMemoryService service = ExternalMemoryService.get();
    private final Label policyBanner = new Label(I18n.get("settings.ai.memory.external.policy.denied"));
    private final Button searchButton = new Button(I18n.get("settings.ai.memory.external.search"));
    private final Button chooseButton = new Button(I18n.get("settings.ai.memory.external.chooseFile"));
    private final VBox rows = new VBox(8);
    private final Label statusLabel = new Label();
    /** The project folder the user chose last; files under it may be picked with the file chooser. */
    private Path projectFolder;
    private long generation;

    /**
     * @param owner        the window the dialogs belong to
     * @param scopes       the scopes an approval can name: global first, then the connections
     * @param currentScope the scope of the Memory tab, the default for a new approval
     */
    ExternalMemorySection(Window owner, List<AiMemoryPane.ScopeChoice> scopes,
                          Supplier<AiMemoryPane.ScopeChoice> currentScope) {
        this.owner = owner;
        this.scopes = List.copyOf(scopes);
        this.currentScope = currentScope;
        setSpacing(8);
        Label title = new Label(I18n.get("settings.ai.memory.external.title"));
        title.setStyle("-fx-font-weight: bold;");
        policyBanner.setWrapText(true);
        policyBanner.setStyle(BANNER_STYLE);
        policyBanner.setVisible(false);
        policyBanner.setManaged(false);
        statusLabel.setWrapText(true);
        statusLabel.setStyle(ERROR_STYLE);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        searchButton.setOnAction(event -> openSearch());
        chooseButton.setOnAction(event -> chooseFile());
        HBox buttons = new HBox(10, searchButton, chooseButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(title, hint(I18n.get("settings.ai.memory.external.hint")), policyBanner, buttons, rows,
            statusLabel);
    }

    /** Checks every approved file now (off the FX thread) and shows the result. Cheap: one stat per file. */
    void refresh() {
        boolean allowed = PolicyManager.effective().externalMemoryFilesAllowed();
        policyBanner.setVisible(!allowed);
        policyBanner.setManaged(!allowed);
        PolicyUiSupport.lockIf(searchButton, !allowed);
        PolicyUiSupport.lockIf(chooseButton, !allowed);
        long current = ++generation;
        CompletableFuture.supplyAsync(() -> allowed ? service.refresh() : service.entries())
            .whenComplete((entries, error) -> Platform.runLater(() -> {
                if (current != generation) {
                    return;
                }
                if (error != null) {
                    showFailure(error);
                    return;
                }
                render(entries, allowed);
            }));
    }

    private void render(List<ExternalMemoryEntry> entries, boolean allowed) {
        rows.getChildren().clear();
        if (entries.isEmpty()) {
            rows.getChildren().add(hint(I18n.get("settings.ai.memory.external.empty")));
            return;
        }
        for (ExternalMemoryEntry entry : entries) {
            rows.getChildren().add(card(entry, allowed));
        }
    }

    private VBox card(ExternalMemoryEntry entry, boolean allowed) {
        Label tool = new Label(ExternalMemoryTexts.toolLabel(entry.tool()));
        tool.setStyle("-fx-font-weight: bold;");
        Label badge = new Label(ExternalMemoryTexts.statusText(entry));
        badge.setStyle(ExternalMemoryTexts.statusStyle(entry));
        HBox head = new HBox(8, tool, badge);
        head.setAlignment(Pos.CENTER_LEFT);
        Label path = hint(entry.path());
        Label scope = hint(I18n.get("settings.ai.memory.external.scopeLine", scopeName(entry)));
        VBox info = new VBox(4, head, path, scope);
        String reason = ExternalMemoryTexts.reasonText(entry);
        if (!reason.isEmpty()) {
            Label reasonLabel = new Label(reason);
            reasonLabel.setWrapText(true);
            info.getChildren().add(reasonLabel);
        }

        CheckBox send = new CheckBox(I18n.get("settings.ai.memory.external.active"));
        send.setSelected(entry.active());
        send.setDisable(!allowed);
        send.setTooltip(allowed ? null : new Tooltip(I18n.get("settings.ai.memory.policy.readOnly")));
        send.setOnAction(event -> setActive(entry.id(), send.isSelected()));
        HBox actions = new HBox(10, send);
        actions.setAlignment(Pos.CENTER_LEFT);
        boolean approvalNeeded = entry.status() == ExternalMemoryStatus.CHANGED
            || entry.status() == ExternalMemoryStatus.REFUSED_SECRET;
        if (approvalNeeded) {
            Button approve = new Button(I18n.get("settings.ai.memory.external.reapprove"));
            approve.setDisable(!allowed);
            approve.setTooltip(allowed ? null : new Tooltip(I18n.get("settings.ai.memory.policy.readOnly")));
            approve.setOnAction(event -> reviewAgain(entry));
            actions.getChildren().add(approve);
        }
        Button remove = new Button(I18n.get("settings.ai.memory.external.remove"));
        remove.setDisable(!allowed);
        remove.setTooltip(allowed ? null : new Tooltip(I18n.get("settings.ai.memory.policy.readOnly")));
        remove.setOnAction(event -> removeEntry(entry));
        actions.getChildren().add(remove);

        VBox card = new VBox(8, info, actions);
        card.setStyle(CARD_STYLE);
        return card;
    }

    private String scopeName(ExternalMemoryEntry entry) {
        if (entry.global()) {
            return I18n.get("settings.ai.memory.scope.global");
        }
        return scopes.stream()
            .filter(choice -> Objects.equals(choice.connectionId(), entry.connectionId()))
            .findFirst()
            .map(AiMemoryPane.ScopeChoice::label)
            .orElse(entry.connectionId());
    }

    private void setActive(String entryId, boolean active) {
        CompletableFuture.runAsync(() -> {
            try {
                service.setActive(entryId, active);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((done, error) -> Platform.runLater(() -> {
            if (error != null) {
                showFailure(error);
            }
            refresh();
        }));
    }

    private void removeEntry(ExternalMemoryEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.ownedBy(confirm, owner);
        confirm.setTitle(I18n.get("settings.ai.memory.external.removeTitle"));
        confirm.setHeaderText(I18n.get("settings.ai.memory.external.removeTitle"));
        confirm.setContentText(I18n.get("settings.ai.memory.external.removeContent"));
        DialogThemeHelper.applyTheme(confirm);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                service.remove(entry.id());
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((done, error) -> Platform.runLater(() -> {
            if (error != null) {
                showFailure(error);
            }
            refresh();
        }));
    }

    /** Reviews a changed file's new text and approves it on confirmation. */
    private void reviewAgain(ExternalMemoryEntry entry) {
        reviewThen(Path.of(entry.path()), Path.of(entry.root()), review ->
            ExternalMemoryAddDialog.reapprove(owner, service, entry, review, scopes).ifPresent(updated -> refresh()));
    }

    private void openSearch() {
        CompletableFuture.supplyAsync(() -> service.discovery().homeCandidates())
            .whenComplete((found, error) -> Platform.runLater(() -> {
                if (error != null) {
                    showFailure(error);
                    return;
                }
                new CandidatePicker(found).showAndWait().ifPresent(candidate ->
                    reviewThen(candidate.path(), candidate.root(), review -> ExternalMemoryAddDialog.approve(owner,
                        service, review, candidate.tool(), scopes, currentScope.get()).ifPresent(entry -> refresh())));
            }));
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("settings.ai.memory.external.chooseFileTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            I18n.get("settings.ai.memory.external.fileFilter"), "*.md", "*.mdc"));
        Path home = service.access().home();
        if (home.toFile().isDirectory()) {
            chooser.setInitialDirectory(home.toFile());
        }
        File chosen = chooser.showOpenDialog(owner);
        if (chosen == null) {
            return;
        }
        Path file = chosen.toPath();
        Path project = projectFolder;
        CompletableFuture.supplyAsync(() -> rootOf(file, project))
            .whenComplete((root, error) -> Platform.runLater(() -> {
                if (error != null) {
                    showFailure(error);
                } else if (root == null) {
                    showMessage(I18n.get("settings.ai.memory.external.reason.outside"));
                } else {
                    reviewThen(file, root, review -> ExternalMemoryAddDialog.approve(owner, service, review,
                        ExternalMemoryTool.OTHER, scopes, currentScope.get()).ifPresent(entry -> refresh()));
                }
            }));
    }

    /** The home folder or the chosen project folder that holds {@code file}, or null when neither does. */
    private Path rootOf(Path file, Path project) {
        try {
            Path real = file.toRealPath();
            for (Path root : project == null ? List.of(service.access().home()) : List.of(service.access().home(), project)) {
                if (real.startsWith(root.toRealPath())) {
                    return root;
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("A chosen memory file is not under an allowed folder");
        }
        return null;
    }

    /** Reads a file for approval off the FX thread, then runs {@code next} on the FX thread with the review. */
    private void reviewThen(Path file, Path root, Consumer<ExternalMemoryService.Review> next) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return service.review(file, root);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((review, error) -> Platform.runLater(() -> {
            if (error != null) {
                showFailure(error);
                return;
            }
            next.accept(review);
        }));
    }

    private void showFailure(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        logger.warn("A reference memory file could not be handled: {}", cause.getClass().getSimpleName());
        showMessage(I18n.get("settings.ai.memory.external.error", cause.getClass().getSimpleName()));
    }

    private void showMessage(String text) {
        statusLabel.setText(text);
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(MutedTextStyle.HINT);
        return label;
    }

    /** The list of files the known locations hold, with the project folder button; picks one candidate. */
    private final class CandidatePicker extends ThemeAwareDialog<ExternalMemoryDiscovery.Candidate> {

        private final ListView<ExternalMemoryDiscovery.Candidate> list = new ListView<>();

        CandidatePicker(List<ExternalMemoryDiscovery.Candidate> found) {
            setTitle(I18n.get("settings.ai.memory.external.search.title"));
            setResizable(true);
            if (owner != null) {
                initOwner(owner);
            }
            ButtonType next = new ButtonType(I18n.get("settings.ai.memory.external.search.next"),
                ButtonBar.ButtonData.OK_DONE);
            getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);

            list.getItems().setAll(found);
            list.setPlaceholder(hint(I18n.get("settings.ai.memory.external.search.none")));
            list.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(ExternalMemoryDiscovery.Candidate item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null
                        : ExternalMemoryTexts.toolLabel(item.tool()) + ": " + item.label());
                    setTooltip(empty || item == null ? null : new Tooltip(item.path().toString()));
                }
            });
            list.setPrefHeight(280);
            Label header = hint(I18n.get("settings.ai.memory.external.search.header"));
            Button project = new Button(I18n.get("settings.ai.memory.external.search.project"));
            project.setOnAction(event -> addProjectFiles());
            VBox content = new VBox(10, header, project, list);
            content.setPadding(new Insets(14));
            getDialogPane().setContent(content);
            getDialogPane().setPrefSize(640, 480);

            Button nextButton = (Button) getDialogPane().lookupButton(next);
            nextButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
            setResultConverter(button -> button == next ? list.getSelectionModel().getSelectedItem() : null);
        }

        private void addProjectFiles() {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.get("settings.ai.memory.external.search.project"));
            Path home = service.access().home();
            if (home.toFile().isDirectory()) {
                chooser.setInitialDirectory(home.toFile());
            }
            File folder = chooser.showDialog(this.getDialogPane().getScene().getWindow());
            if (folder == null) {
                return;
            }
            Path chosen = folder.toPath();
            projectFolder = chosen;
            CompletableFuture.supplyAsync(() -> service.discovery().projectCandidates(chosen))
                .whenComplete((found, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        showFailure(error);
                        return;
                    }
                    list.getItems().addAll(found);
                }));
        }
    }
}
