package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.llama.LlamaModelPurpose;
import de.kortty.ai.llama.GgufMetadataReader;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.rag.CancellationToken;
import de.kortty.rag.RagConfigurationManager;
import de.kortty.rag.RagCoordinator;
import de.kortty.rag.RagContextBuilder;
import de.kortty.rag.RagRuntimeService;
import de.kortty.rag.RagScanPreview;
import de.kortty.rag.RagSource;
import de.kortty.rag.RagSourceFormatRegistry;
import de.kortty.rag.RagSourceScanner;
import de.kortty.rag.RagSourceStatus;
import de.kortty.rag.RagSourceSynchronizer;
import de.kortty.rag.RagSourceType;
import de.kortty.rag.RagSourceWatchService;
import de.kortty.rag.RagSecretSupport;
import de.kortty.rag.RagStatus;
import de.kortty.rag.RagStore;
import de.kortty.rag.RagStoreType;
import de.kortty.rag.RagSyncMode;
import de.kortty.rag.RagSyncResult;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Beginner-first knowledge-store UI backed by the transactional local RAG services. */
final class RagKnowledgeStorePane extends VBox implements AutoCloseable {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1024;

    private final KorTTYApplication app;
    private final Window owner;
    private final Runnable profilesChanged;
    private final Runnable openEmbeddingSetup;
    private final RagConfigurationManager configuration;
    private final RagRuntimeService runtimeService;
    private final RagCoordinator coordinator;
    private final AutoCloseable statusSubscription;
    private final RagSourceScanner scanner = new RagSourceScanner();
    private final ObservableList<RagStore> stores = FXCollections.observableArrayList();
    private final ObservableList<RagSource> sources = FXCollections.observableArrayList();
    private final TableView<RagStore> storeTable = new TableView<>(stores);
    private final TableView<RagSource> sourceTable = new TableView<>(sources);
    private final Label status = new Label();
    private final Map<String, RagStatus> sourceStatuses = new HashMap<>();
    private final Map<String, RagSyncResult> sourceResults = new HashMap<>();
    private CancellationToken.Source activeCancellation;

    RagKnowledgeStorePane(
        KorTTYApplication app,
        Window owner,
        Runnable profilesChanged,
        Runnable openEmbeddingSetup
    ) throws IOException {
        this.app = app;
        this.owner = owner;
        this.profilesChanged = profilesChanged != null ? profilesChanged : () -> { };
        this.openEmbeddingSetup = openEmbeddingSetup != null ? openEmbeddingSetup : () -> { };
        this.configuration = new RagConfigurationManager();
        this.runtimeService = new RagRuntimeService(configuration.file());
        this.coordinator = RagCoordinator.getDefault();
        this.statusSubscription = coordinator.addStatusListener(current -> Platform.runLater(() -> {
            sourceStatuses.put(current.sourceId(), current);
            sourceTable.refresh();
            RagKnowledgeStorePresentation.Progress progress =
                RagKnowledgeStorePresentation.progress(current);
            String phase = progress.message().isBlank()
                ? progress.status().name() : progress.message();
            status.setText(I18n.get("ai.rag.indexing.progress", phase,
                progress.indexedDocuments(), progress.indexedChunks(), progress.problemCount(),
                progress.percent()));
        }));
        // Startup reconciliation and recursive watch registration perform filesystem I/O.
        // Register the listener first, then start them away from the JavaFX application thread.
        CompletableFuture.runAsync(this.coordinator::start);
        setPadding(new Insets(8));
        setSpacing(10);

        Label intro = new Label(I18n.get("ai.rag.intro"));
        intro.setWrapText(true);
        configureStoreTable();
        configureSourceTable();
        storeTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> refreshSources(selected));

        HBox storeActions = storeActions();
        HBox sourceActions = sourceActions();
        status.setWrapText(true);
        status.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: -fx-text-inner-color;");
        VBox storeBox = new VBox(6, sectionTitle(I18n.get("ai.rag.stores")), storeTable, storeActions);
        VBox sourceBox = new VBox(6, sectionTitle(I18n.get("ai.rag.sources")), sourceTable, sourceActions);
        VBox.setVgrow(storeTable, Priority.ALWAYS);
        VBox.setVgrow(sourceTable, Priority.ALWAYS);
        getChildren().addAll(intro, storeBox, sourceBox, status);
        VBox.setVgrow(storeBox, Priority.ALWAYS);
        VBox.setVgrow(sourceBox, Priority.ALWAYS);
        refresh();
    }

    void refresh() {
        String selectedId = selectedStore() != null ? selectedStore().id() : null;
        stores.setAll(configuration.listStores());
        if (selectedId != null) {
            stores.stream().filter(store -> store.id().equals(selectedId)).findFirst()
                .ifPresent(storeTable.getSelectionModel()::select);
        }
        if (storeTable.getSelectionModel().getSelectedItem() == null && !stores.isEmpty()) {
            storeTable.getSelectionModel().selectFirst();
        }
        refreshSources(selectedStore());
        ensureAutomaticWatchers();
    }

    private void configureStoreTable() {
        storeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        storeTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        storeTable.setPlaceholder(new Label(I18n.get("ai.rag.empty")));
        TableColumn<RagStore, String> name = column(I18n.get("ai.rag.column.name"), RagStore::displayName);
        TableColumn<RagStore, String> type = column(I18n.get("ai.rag.column.type"), store ->
            store.type() == RagStoreType.LOCAL_HNSW ? I18n.get("ai.rag.type.local") : "Qdrant");
        TableColumn<RagStore, String> model = column(I18n.get("ai.rag.column.embedding"), store ->
            store.embeddingModelId().isBlank() ? "—" : store.embeddingModelId());
        TableColumn<RagStore, String> roles = column(I18n.get("ai.rag.column.roles"), this::roleText);
        TableColumn<RagStore, String> count = column(I18n.get("ai.rag.column.sources"), store ->
            Integer.toString(configuration.getSources(store.id()).size()));
        name.setMinWidth(170);
        model.setMinWidth(180);
        storeTable.getColumns().addAll(List.of(name, type, model, roles, count));
    }

    private void configureSourceTable() {
        sourceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sourceTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        sourceTable.setPlaceholder(new Label(I18n.get("ai.rag.sources.empty")));
        TableColumn<RagSource, String> path = column(I18n.get("ai.rag.column.path"), source -> source.path().toString());
        TableColumn<RagSource, String> type = column(I18n.get("ai.rag.column.type"), source ->
            I18n.get("ai.rag.sourceType." + source.type().name().toLowerCase(Locale.ROOT)));
        TableColumn<RagSource, String> mode = column(I18n.get("ai.rag.column.sync"), source ->
            I18n.get("ai.rag.sync." + source.syncMode().name().toLowerCase(Locale.ROOT)));
        TableColumn<RagSource, String> state = column(I18n.get("ai.rag.column.status"), source -> {
            RagStatus current = sourceStatuses.get(source.id());
            if (current == null || current.message().isBlank()) {
                return current != null ? current.status().name() : source.lastStatus().name();
            }
            return current.status().name() + " — " + current.message();
        });
        TableColumn<RagSource, String> documents = column(I18n.get("ai.rag.column.filesChunks"), source -> {
            RagSyncResult result = sourceResults.get(source.id());
            return result != null ? result.documents() + " / " + result.chunks()
                : source.indexedFiles() + " / " + source.indexedChunks();
        });
        TableColumn<RagSource, String> updated = column(I18n.get("ai.rag.column.updated"), source -> {
            RagSyncResult result = sourceResults.get(source.id());
            java.time.Instant value = result != null ? result.completedAt() : source.lastSuccessfulIndex();
            return value != null ? DATE_TIME.format(value.atZone(ZoneId.systemDefault())) : "—";
        });
        TableColumn<RagSource, String> problems = column(I18n.get("ai.rag.column.problems"), source -> {
            RagSyncResult result = sourceResults.get(source.id());
            return Integer.toString(result != null ? result.problems() : source.lastProblemCount());
        });
        path.setMinWidth(280);
        sourceTable.getColumns().addAll(List.of(path, type, mode, state, documents, problems, updated));
    }

    private HBox storeActions() {
        Button create = new Button(I18n.get("ai.rag.create"));
        create.setOnAction(event -> createStoreWizard());
        Button configure = new Button(I18n.get("ai.rag.configure"));
        configure.setOnAction(event -> configureStore());
        Button delete = new Button(I18n.get("ai.rag.deleteStore"));
        delete.setOnAction(event -> deleteStore());
        Button test = new Button(I18n.get("ai.rag.testSearch"));
        test.setOnAction(event -> testSearch());
        Button refresh = new Button(I18n.get("ai.manager.refresh"));
        refresh.setOnAction(event -> refresh());
        return actionBox(create, configure, delete, test, refresh);
    }

    private HBox sourceActions() {
        Button files = new Button(I18n.get("ai.rag.addFiles"));
        files.setOnAction(event -> addFiles());
        Button directory = new Button(I18n.get("ai.rag.addFolder"));
        directory.setOnAction(event -> addDirectory());
        Button update = new Button(I18n.get("ai.rag.updateNow"));
        update.setOnAction(event -> synchronizeSelected());
        Button cancel = new Button(I18n.get("ai.rag.cancelIndexing"));
        cancel.setOnAction(event -> cancelIndexing());
        Button mode = new Button(I18n.get("ai.rag.changeMode"));
        mode.setOnAction(event -> changeSyncMode());
        Button advanced = new Button(I18n.get("ai.rag.advanced"));
        advanced.setOnAction(event -> configureSourceAdvanced());
        Button toggle = new Button(I18n.get("ai.rag.toggle"));
        toggle.setOnAction(event -> toggleSource());
        Button remove = new Button(I18n.get("ai.rag.removeSource"));
        remove.setOnAction(event -> removeSources());
        return actionBox(files, directory, update, cancel, mode, advanced, toggle, remove);
    }

    private void createStoreWizard() {
        StoreDraft draft = showStoreDialog(null);
        if (draft == null) {
            return;
        }
        List<RagSource> selectedSources = chooseInitialSources();
        if (selectedSources.isEmpty()) {
            return;
        }
        scanAndConfirm(selectedSources, List.of(),
            previews -> createStore(draft, selectedSources, previews));
    }

    private void createStore(
        StoreDraft draft,
        List<RagSource> selectedSources,
        List<RagScanPreview> confirmedPreviews
    ) {
        String id = UUID.randomUUID().toString();
        Path localDirectory = KorTTYApplication.getConfigDirectory().resolve("rag").resolve("stores").resolve(id);
        RagStore store;
        try {
            store = draft.type == RagStoreType.LOCAL_HNSW
                ? new RagStore(id, draft.name, draft.type, localDirectory, null, "kortty_rag", "",
                    draft.embeddingModelId, draft.dimensions, draft.text, draft.coding, draft.autonomous)
                : new RagStore(id, draft.name, draft.type, null, URI.create(draft.endpoint), draft.collection,
                    RagSecretSupport.protect(draft.apiKey), draft.embeddingModelId, draft.dimensions,
                    draft.text, draft.coding, draft.autonomous);
            configuration.create(store);
            configuration.setSources(store.id(), selectedSources);
            applyProfileAssignments(store);
            refresh();
            storeTable.getSelectionModel().select(store);
            synchronizeConfirmed(store, confirmedPreviews);
        } catch (Exception error) {
            try {
                configuration.delete(id);
            } catch (Exception ignored) {
            }
            show(Alert.AlertType.ERROR, message(error));
        }
    }

    private void configureStore() {
        RagStore selected = selectedStore();
        if (selected == null) {
            return;
        }
        StoreDraft draft = showStoreDialog(selected);
        if (draft == null) {
            return;
        }
        boolean embeddingChanged = !selected.embeddingModelId().equals(draft.embeddingModelId)
            || selected.embeddingDimensions() != draft.dimensions;
        if (embeddingChanged && !configuration.getSources(selected.id()).isEmpty()) {
            show(Alert.AlertType.WARNING, I18n.get("ai.rag.embeddingChangeBlocked"));
            return;
        }
        try {
            RagStore updated = new RagStore(selected.id(), draft.name, draft.type,
                draft.type == RagStoreType.LOCAL_HNSW ? selected.localDirectory() : null,
                draft.type == RagStoreType.QDRANT ? URI.create(draft.endpoint) : null,
                draft.collection,
                draft.apiKey.isBlank() ? selected.apiKey() : RagSecretSupport.protect(draft.apiKey),
                draft.embeddingModelId, draft.dimensions,
                draft.text, draft.coding, draft.autonomous);
            configuration.update(updated);
            applyProfileAssignments(updated);
            refresh();
        } catch (Exception error) {
            show(Alert.AlertType.ERROR, message(error));
        }
    }

    private StoreDraft showStoreDialog(RagStore existing) {
        List<LlamaModel> localModels = LlamaModelRegistry
            .inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm")).list().stream()
            .filter(model -> model.getPurpose() == LlamaModelPurpose.EMBEDDING)
            .toList();
        if (localModels.isEmpty()) {
            show(Alert.AlertType.WARNING, I18n.get("ai.rag.embeddingMissing"));
            openEmbeddingSetup.run();
            return null;
        }
        Dialog<StoreDraft> dialog = new Dialog<>();
        dialog.initOwner(owner);
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(existing == null ? I18n.get("ai.rag.create") : I18n.get("ai.rag.configure"));
        dialog.setHeaderText(I18n.get("ai.rag.storeWizard.header"));
        ButtonType save = new ButtonType(I18n.get("settings.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField name = new TextField(existing != null ? existing.displayName() : "");
        ComboBox<RagStoreType> type = new ComboBox<>(FXCollections.observableArrayList(RagStoreType.values()));
        type.setValue(existing != null ? existing.type() : RagStoreType.LOCAL_HNSW);
        ComboBox<LlamaModel> embedding = new ComboBox<>(FXCollections.observableArrayList(localModels));
        embedding.setConverter(new StringConverter<>() {
            @Override public String toString(LlamaModel value) { return value != null ? value.getDisplayName() : ""; }
            @Override public LlamaModel fromString(String value) { return null; }
        });
        String preferred = existing != null ? existing.embeddingModelId() : configuredEmbeddingModelId();
        localModels.stream().filter(model -> model.getId().equals(preferred)).findFirst()
            .ifPresentOrElse(embedding::setValue, () -> embedding.setValue(localModels.getFirst()));
        Spinner<Integer> dimensions = new Spinner<>(1, 65_536,
            existing != null && existing.embeddingDimensions() > 0 ? existing.embeddingDimensions() : DEFAULT_EMBEDDING_DIMENSIONS);
        dimensions.setEditable(true);
        java.util.function.Consumer<LlamaModel> detectDimensions = model -> {
            if (model == null || existing != null) return;
            try {
                GgufMetadataReader.embeddingDimensions(model.getModelPath())
                    .ifPresent(value -> dimensions.getValueFactory().setValue(value));
            } catch (IOException ignored) {
                // Imported GGUFs without readable metadata keep the conservative editable default.
            }
        };
        embedding.valueProperty().addListener((ignored, previous, current) -> detectDimensions.accept(current));
        detectDimensions.accept(embedding.getValue());
        CheckBox text = new CheckBox(I18n.get("ai.rag.role.text"));
        CheckBox coding = new CheckBox(I18n.get("ai.rag.role.coding"));
        CheckBox autonomous = new CheckBox(I18n.get("ai.rag.role.autonomous"));
        text.setSelected(existing == null || existing.textEnabled());
        coding.setSelected(existing == null || existing.codingEnabled());
        autonomous.setSelected(existing != null && existing.autonomousEnabled());
        TextField endpoint = new TextField(existing != null && existing.endpoint() != null ? existing.endpoint().toString() : "http://127.0.0.1:6333");
        TextField collection = new TextField(existing != null ? existing.collectionName() : "kortty_rag");
        TextField apiKey = new TextField();
        apiKey.setPromptText(existing != null && !existing.apiKey().isBlank()
            ? I18n.get("ai.rag.qdrant.apiKey.unchanged") : "");

        GridPane grid = formGrid();
        int row = 0;
        grid.add(new Label(I18n.get("ai.rag.column.name")), 0, row); grid.add(name, 1, row++);
        grid.add(new Label(I18n.get("ai.rag.column.embedding")), 0, row); grid.add(embedding, 1, row++);
        grid.add(new Label(I18n.get("ai.rag.column.roles")), 0, row); grid.add(new HBox(8, text, coding, autonomous), 1, row++);

        GridPane advancedGrid = formGrid();
        int advancedRow = 0;
        advancedGrid.add(new Label(I18n.get("ai.rag.column.type")), 0, advancedRow); advancedGrid.add(type, 1, advancedRow++);
        advancedGrid.add(new Label(I18n.get("ai.rag.embeddingDimensions")), 0, advancedRow); advancedGrid.add(dimensions, 1, advancedRow++);
        advancedGrid.add(new Label(I18n.get("ai.rag.qdrant.endpoint")), 0, advancedRow); advancedGrid.add(endpoint, 1, advancedRow++);
        advancedGrid.add(new Label(I18n.get("ai.rag.qdrant.collection")), 0, advancedRow); advancedGrid.add(collection, 1, advancedRow++);
        advancedGrid.add(new Label(I18n.get("ai.rag.qdrant.apiKey")), 0, advancedRow); advancedGrid.add(apiKey, 1, advancedRow);
        Runnable updateAdvancedState = () -> {
            boolean qdrant = type.getValue() == RagStoreType.QDRANT;
            endpoint.setDisable(!qdrant);
            collection.setDisable(!qdrant);
            apiKey.setDisable(!qdrant);
        };
        type.valueProperty().addListener((ignored, previous, current) -> updateAdvancedState.run());
        updateAdvancedState.run();
        if (existing != null && !configuration.getSources(existing.id()).isEmpty()) {
            type.setDisable(true);
        }
        TitledPane advanced = new TitledPane(I18n.get("ai.rag.advanced"), advancedGrid);
        advanced.setExpanded(existing != null && existing.type() == RagStoreType.QDRANT);
        dialog.getDialogPane().setContent(new VBox(8, grid, advanced));
        dialog.setResultConverter(button -> {
            if (button != save || name.getText().isBlank() || embedding.getValue() == null) {
                return null;
            }
            return new StoreDraft(name.getText().trim(), type.getValue(), embedding.getValue().getId(),
                dimensions.getValue(), text.isSelected(), coding.isSelected(), autonomous.isSelected(),
                endpoint.getText().trim(), collection.getText().trim(), apiKey.getText().trim());
        });
        return dialog.showAndWait().orElse(null);
    }

    private List<RagSource> chooseInitialSources() {
        ChoiceDialog<String> dialog = new ChoiceDialog<>(I18n.get("ai.rag.addFolder"),
            I18n.get("ai.rag.addFiles"), I18n.get("ai.rag.addFolder"));
        dialog.initOwner(owner);
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.rag.create"));
        dialog.setHeaderText(I18n.get("ai.rag.chooseSources"));
        Optional<String> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return List.of();
        }
        return selected.get().equals(I18n.get("ai.rag.addFiles")) ? chooseFiles() : chooseDirectory();
    }

    private void addFiles() {
        addSources(chooseFiles());
    }

    private void addDirectory() {
        addSources(chooseDirectory());
    }

    private List<RagSource> chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("ai.rag.addFiles"));
        List<String> patterns = new RagSourceFormatRegistry().allowedSuffixes().stream()
            .map(value -> value.startsWith(".") ? "*" + value : value).toList();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("ai.rag.supportedFiles"), patterns));
        List<java.io.File> selected = chooser.showOpenMultipleDialog(owner);
        return selected == null ? List.of() : selected.stream().map(file -> RagSource.file(file.toPath())).toList();
    }

    private List<RagSource> chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("ai.rag.addFolder"));
        java.io.File selected = chooser.showDialog(owner);
        return selected == null ? List.of() : List.of(RagSource.directory(selected.toPath()));
    }

    private void addSources(List<RagSource> candidates) {
        RagStore store = selectedStore();
        if (store == null || candidates.isEmpty()) {
            return;
        }
        List<RagSource> existing = configuration.getSources(store.id());
        scanAndConfirm(candidates, existing,
            previews -> addConfirmedSources(store, existing, candidates, previews));
    }

    private void addConfirmedSources(
        RagStore store,
        List<RagSource> existing,
        List<RagSource> candidates,
        List<RagScanPreview> confirmedPreviews
    ) {
        List<RagSource> updated = new ArrayList<>(existing);
        updated.addAll(candidates);
        try {
            configuration.setSources(store.id(), updated);
            refreshSources(store);
            ensureAutomaticWatchers();
            synchronizeConfirmed(store, confirmedPreviews);
        } catch (Exception error) {
            show(Alert.AlertType.ERROR, message(error));
        }
    }

    private List<RagScanPreview> scanPreviews(
        List<RagSource> candidates,
        List<RagSource> existing,
        CancellationToken token
    ) {
        List<RagScanPreview> previews = new ArrayList<>();
        List<RagSource> accepted = new ArrayList<>(existing);
        for (RagSource candidate : candidates) {
            token.throwIfCancelled();
            Optional<RagSource> overlap = scanner.findOverlap(candidate, accepted);
            if (overlap.isPresent()) {
                throw new IllegalArgumentException(
                    I18n.get("ai.rag.overlap", candidate.path(), overlap.get().path()));
            }
            RagScanPreview preview = scanner.preview(candidate, token);
            previews.add(preview);
            accepted.add(candidate);
        }
        return List.copyOf(previews);
    }

    private void scanAndConfirm(
        List<RagSource> candidates,
        List<RagSource> existing,
        Consumer<List<RagScanPreview>> confirmed
    ) {
        if (activeCancellation != null) {
            status.setText(I18n.get("ai.rag.indexing.alreadyRunning"));
            return;
        }
        activeCancellation = CancellationToken.source();
        CancellationToken.Source cancellation = activeCancellation;
        status.setText(I18n.get("ai.rag.preview.scanning"));
        CompletableFuture.supplyAsync(() -> scanPreviews(candidates, existing, cancellation.token()))
            .whenComplete((previews, error) -> Platform.runLater(() -> {
                activeCancellation = null;
                if (error != null) {
                    Throwable failure = rootCause(error);
                    if (failure instanceof java.util.concurrent.CancellationException) {
                        status.setText(I18n.get("ai.rag.indexing.cancelling"));
                    } else {
                        show(failure instanceof IllegalArgumentException
                            ? Alert.AlertType.WARNING : Alert.AlertType.ERROR, message(failure));
                    }
                    return;
                }
                if (showPreviewAndConfirm(previews) != null) {
                    confirmed.accept(previews);
                }
            }));
    }

    private List<RagScanPreview> showPreviewAndConfirm(List<RagScanPreview> previews) {
        RagKnowledgeStorePresentation.Preview preview = RagKnowledgeStorePresentation.preview(previews);
        ObservableList<RagKnowledgeStorePresentation.PreviewRow> rows =
            FXCollections.observableArrayList(preview.rows());
        TableView<RagKnowledgeStorePresentation.PreviewRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<RagKnowledgeStorePresentation.PreviewRow, String> path =
            column(I18n.get("ai.rag.column.path"), RagKnowledgeStorePresentation.PreviewRow::path);
        TableColumn<RagKnowledgeStorePresentation.PreviewRow, String> format =
            column(I18n.get("ai.rag.preview.column.format"), row -> row.format().isBlank() ? "—" : row.format());
        TableColumn<RagKnowledgeStorePresentation.PreviewRow, String> size =
            column(I18n.get("ai.rag.preview.column.size"), row -> row.rawSize() < 0 ? "—" : formatBytes(row.rawSize()));
        TableColumn<RagKnowledgeStorePresentation.PreviewRow, String> state =
            column(I18n.get("ai.rag.column.status"), row -> previewStateText(row.state()));
        TableColumn<RagKnowledgeStorePresentation.PreviewRow, String> reason =
            column(I18n.get("ai.rag.preview.column.reason"), RagKnowledgeStorePresentation.PreviewRow::reason);
        path.setMinWidth(300);
        reason.setMinWidth(220);
        table.getColumns().addAll(List.of(path, format, size, state, reason));
        table.setPrefHeight(380);

        Label summary = new Label(I18n.get("ai.rag.preview.summary",
            preview.acceptedFiles(), preview.unchangedFiles(), preview.filesToIndex(),
            formatBytes(preview.acceptedBytes()), preview.formats(), preview.skippedFiles()));
        summary.setWrapText(true);
        Dialog<ButtonType> confirm = new Dialog<>();
        confirm.initOwner(owner);
        DialogThemeHelper.applyTheme(confirm);
        confirm.setTitle(I18n.get("ai.rag.preview.title"));
        confirm.setHeaderText(I18n.get("ai.rag.preview.header"));
        confirm.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        confirm.getDialogPane().setContent(new VBox(8, summary, table));
        confirm.getDialogPane().setPrefWidth(760);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && preview.canConfirm()
            ? previews : null;
    }

    private void synchronizeSelected() {
        RagStore store = selectedStore();
        List<RagSource> selected = List.copyOf(sourceTable.getSelectionModel().getSelectedItems());
        if (store != null) {
            List<RagSource> requested = selected.isEmpty() ? configuration.getSources(store.id()) : selected;
            scanAndConfirm(requested, List.of(), previews -> synchronizeConfirmed(store, previews));
        }
    }

    private void synchronizeConfirmed(RagStore store, List<RagScanPreview> confirmedPreviews) {
        if (confirmedPreviews.isEmpty()) {
            return;
        }
        if (activeCancellation != null) {
            status.setText(I18n.get("ai.rag.indexing.alreadyRunning"));
            return;
        }
        activeCancellation = CancellationToken.source();
        CancellationToken token = activeCancellation.token();
        status.setText(I18n.get("ai.rag.indexing.started", confirmedPreviews.size()));
        coordinator.synchronizeConfirmed(store, confirmedPreviews, token)
            .whenComplete((results, error) -> Platform.runLater(() -> {
            Throwable completionError = error;
            activeCancellation = null;
            if (results != null) {
                results.forEach(result -> sourceResults.put(result.sourceId(), result));
            }
            try {
                configuration.reload();
            } catch (IOException reloadError) {
                if (completionError == null) completionError = reloadError;
            }
            refreshSources(configuration.findStore(store.id()).orElse(store));
            sourceTable.refresh();
            status.setText(completionError == null ? completionSummary(results)
                : I18n.get("ai.rag.indexing.failed") + ": "
                    + RagKnowledgeStorePresentation.failureMessage(completionError));
            }));
    }

    private void cancelIndexing() {
        if (activeCancellation != null) {
            activeCancellation.cancel();
            status.setText(I18n.get("ai.rag.indexing.cancelling"));
        }
    }

    private void changeSyncMode() {
        RagStore store = selectedStore();
        RagSource source = sourceTable.getSelectionModel().getSelectedItem();
        if (store == null || source == null) {
            return;
        }
        ChoiceDialog<RagSyncMode> dialog = new ChoiceDialog<>(source.syncMode(), RagSyncMode.values());
        dialog.initOwner(owner);
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.rag.changeMode"));
        dialog.setHeaderText(I18n.get("ai.rag.changeMode.header"));
        dialog.showAndWait().ifPresent(mode -> changeSyncMode(store, source, mode));
    }

    private void changeSyncMode(RagStore store, RagSource source, RagSyncMode mode) {
        try {
            configuration.setSources(store.id(), RagKnowledgeStorePresentation.withSyncMode(
                configuration.getSources(store.id()), source.id(), mode));
            refreshSources(store);
            ensureAutomaticWatchers();
        } catch (Exception error) {
            show(Alert.AlertType.ERROR, message(error));
        }
    }

    private void configureSourceAdvanced() {
        RagStore store = selectedStore();
        RagSource source = sourceTable.getSelectionModel().getSelectedItem();
        if (store == null || source == null) {
            return;
        }
        Dialog<RagSource> dialog = new Dialog<>();
        dialog.initOwner(owner);
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.rag.advanced"));
        ButtonType save = new ButtonType(I18n.get("settings.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        CheckBox recursive = new CheckBox(I18n.get("ai.rag.recursive"));
        recursive.setSelected(source.recursive());
        recursive.setDisable(source.type() == RagSourceType.FILE);
        CheckBox gitIgnore = new CheckBox(I18n.get("ai.rag.gitignore"));
        gitIgnore.setSelected(source.respectGitIgnore());
        Spinner<Integer> maxMiB = new Spinner<>(1, 1024, (int) Math.max(1, source.maxFileBytes() / (1024 * 1024)));
        maxMiB.setEditable(true);
        TextArea includes = new TextArea(String.join("\n", source.includePatterns()));
        TextArea excludes = new TextArea(String.join("\n", source.excludePatterns()));
        includes.setPrefRowCount(3); excludes.setPrefRowCount(3);
        VBox content = new VBox(8, recursive, gitIgnore,
            new Label(I18n.get("ai.rag.maxFileMiB")), maxMiB,
            new Label(I18n.get("ai.rag.includeGlobs")), includes,
            new Label(I18n.get("ai.rag.excludeGlobs")), excludes);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == save ? source.withAdvancedOptions(
            recursive.isSelected(), gitIgnore.isSelected(), maxMiB.getValue() * 1024L * 1024L,
            lines(includes.getText()), lines(excludes.getText())) : null);
        dialog.showAndWait().ifPresent(updated -> replaceSource(store, source, updated));
    }

    private void toggleSource() {
        RagStore store = selectedStore();
        RagSource source = sourceTable.getSelectionModel().getSelectedItem();
        if (store != null && source != null) {
            replaceSource(store, source, source.withEnabled(!source.enabled()));
        }
    }

    private void replaceSource(RagStore store, RagSource previous, RagSource replacement) {
        try {
            List<RagSource> updated = RagKnowledgeStorePresentation.replaceSource(
                configuration.getSources(store.id()), previous.id(), ignored -> replacement);
            configuration.setSources(store.id(), updated);
            refreshSources(store);
            ensureAutomaticWatchers();
        } catch (Exception error) {
            show(Alert.AlertType.ERROR, message(error));
        }
    }

    private void removeSources() {
        RagStore store = selectedStore();
        List<RagSource> selected = List.copyOf(sourceTable.getSelectionModel().getSelectedItems());
        if (store == null || selected.isEmpty() || !confirm(I18n.get("ai.rag.remove.confirm", selected.size()))) {
            return;
        }
        coordinator.removeSources(store, selected).thenRun(() -> {
            try {
                Set<String> ids = selected.stream().map(RagSource::id).collect(java.util.stream.Collectors.toSet());
                configuration.setSources(store.id(), RagKnowledgeStorePresentation.removeSources(
                    configuration.getSources(store.id()), ids));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                show(Alert.AlertType.ERROR, RagKnowledgeStorePresentation.failureMessage(error));
            }
            refreshSources(store);
            ensureAutomaticWatchers();
        }));
    }

    private void deleteStore() {
        RagStore store = selectedStore();
        if (store == null || !confirm(I18n.get("ai.rag.delete.confirm", store.displayName()))) {
            return;
        }
        if (activeCancellation != null) {
            status.setText(I18n.get("ai.rag.indexing.alreadyRunning"));
            return;
        }
        List<RagSource> allSources = configuration.getSources(store.id());
        RagKnowledgeStorePresentation.removeStoreVectors(
            store, allSources, coordinator::removeSources, () -> {
                try {
                    configuration.delete(store.id());
                    deleteLocalIndex(store);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }).whenComplete((ignored, error) -> Platform.runLater(() -> {
                if (error != null) {
                    show(Alert.AlertType.ERROR, RagKnowledgeStorePresentation.failureMessage(error));
                    return;
                }
                try {
                    // Profile assignments and the dialog's observable lists belong to JavaFX and
                    // must only be mutated after the background vector cleanup returns here.
                    removeStoreFromProfiles(store.id());
                    coordinator.refreshConfiguration();
                    refresh();
                } catch (Exception cleanupError) {
                    show(Alert.AlertType.ERROR, RagKnowledgeStorePresentation.failureMessage(cleanupError));
                }
            }));
    }

    private static void deleteLocalIndex(RagStore store) throws IOException {
        if (store.type() != RagStoreType.LOCAL_HNSW || store.localDirectory() == null
            || !java.nio.file.Files.exists(store.localDirectory())) {
            return;
        }
        Path allowedRoot = KorTTYApplication.getConfigDirectory().resolve("rag/stores").toAbsolutePath().normalize();
        Path directory = store.localDirectory().toAbsolutePath().normalize();
        if (!directory.startsWith(allowedRoot) || directory.equals(allowedRoot)) {
            throw new IOException("Refusing to delete a knowledge-store index outside " + allowedRoot);
        }
        try (var paths = java.nio.file.Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    private void testSearch() {
        RagStore store = selectedStore();
        if (store == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(owner);
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.rag.testSearch"));
        dialog.setHeaderText(I18n.get("ai.rag.testSearch.header"));
        dialog.showAndWait().filter(value -> !value.isBlank()).ifPresent(query -> {
            status.setText(I18n.get("ai.rag.testSearch.running"));
            CompletableFuture.supplyAsync(() -> {
                try {
                    return runtimeService.retrieve(List.of(store.id()), query, 16_000, CancellationToken.NONE);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }).whenComplete((context, error) -> Platform.runLater(() -> {
                if (error != null) {
                    show(Alert.AlertType.ERROR, RagKnowledgeStorePresentation.failureMessage(error));
                    return;
                }
                TextArea result = new TextArea(context.text().isBlank()
                    ? I18n.get("ai.rag.testSearch.noResults") : context.text());
                result.setEditable(false); result.setWrapText(true); result.setPrefSize(760, 460);
                Dialog<Void> output = new Dialog<>();
                output.initOwner(owner); DialogThemeHelper.applyTheme(output);
                output.setTitle(I18n.get("ai.rag.testSearch"));
                output.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                output.getDialogPane().setContent(result); output.showAndWait();
            }));
        });
    }

    private void refreshSources(RagStore store) {
        sources.setAll(store != null ? configuration.getSources(store.id()) : List.of());
        sourceTable.refresh();
    }

    private void ensureAutomaticWatchers() {
        // Configuration reload and recursive watch registration may traverse thousands of
        // directories; neither operation belongs on the JavaFX application thread.
        CompletableFuture.runAsync(coordinator::refreshConfiguration).exceptionally(error -> {
            Platform.runLater(() -> status.setText(
                I18n.get("ai.rag.indexing.failed") + ": "
                    + RagKnowledgeStorePresentation.failureMessage(error)));
            return null;
        });
    }

    private void applyProfileAssignments(RagStore store) throws Exception {
        GlobalSettings settings = settings();
        if (settings == null) return;
        removeStoreFromProfilesInMemory(settings, store.id());
        if (store.textEnabled()) addStoreToProfile(settings, settings.getTextAiProfileId(), store.id());
        if (store.codingEnabled()) addStoreToProfile(settings, settings.getCodingAiProfileId(), store.id());
        app.getGlobalSettingsManager().save();
        profilesChanged.run();
    }

    private void removeStoreFromProfiles(String storeId) throws Exception {
        GlobalSettings settings = settings();
        if (settings == null) return;
        removeStoreFromProfilesInMemory(settings, storeId);
        app.getGlobalSettingsManager().save();
        profilesChanged.run();
    }

    private static void removeStoreFromProfilesInMemory(GlobalSettings settings, String storeId) {
        for (AiProfile profile : settings.getAiProfiles()) {
            profile.setRagStoreIds(profile.getRagStoreIds().stream().filter(id -> !id.equals(storeId)).toList());
        }
    }

    private static void addStoreToProfile(GlobalSettings settings, String profileId, String storeId) {
        if (profileId == null) return;
        settings.getAiProfiles().stream().filter(profile -> profileId.equals(profile.getId())).findFirst().ifPresent(profile -> {
            List<String> ids = new ArrayList<>(profile.getRagStoreIds());
            if (!ids.contains(storeId)) ids.add(storeId);
            profile.setRagStoreIds(ids);
        });
    }

    private GlobalSettings settings() {
        return app != null && app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    private String configuredEmbeddingModelId() {
        GlobalSettings settings = settings();
        return settings != null && settings.getRagEmbeddingModelId() != null ? settings.getRagEmbeddingModelId() : "";
    }

    private RagStore selectedStore() { return storeTable.getSelectionModel().getSelectedItem(); }

    private String roleText(RagStore store) {
        List<String> roles = new ArrayList<>();
        if (store.textEnabled()) roles.add(I18n.get("ai.rag.role.text"));
        if (store.codingEnabled()) roles.add(I18n.get("ai.rag.role.coding"));
        if (store.autonomousEnabled()) roles.add(I18n.get("ai.rag.role.autonomous"));
        return String.join(", ", roles);
    }

    private static String completionSummary(List<RagSyncResult> results) {
        RagKnowledgeStorePresentation.Completion completion =
            RagKnowledgeStorePresentation.completion(results);
        if (!completion.detailed()) return I18n.get("ai.rag.indexing.complete");
        return I18n.get("ai.rag.indexing.summary", completion.indexedDocuments(),
            completion.unchangedDocuments(), completion.removedDocuments(), completion.skippedDocuments());
    }

    @Override
    public void close() {
        if (activeCancellation != null) activeCancellation.cancel();
        try { statusSubscription.close(); } catch (Exception ignored) { }
    }

    private static GridPane formGrid() {
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(9); grid.setPadding(new Insets(8));
        return grid;
    }

    private static HBox actionBox(javafx.scene.Node... nodes) {
        HBox box = new HBox(8, nodes); box.setAlignment(Pos.CENTER_LEFT); return box;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text); label.setStyle("-fx-font-weight: bold; -fx-font-size: 1em;"); return label;
    }

    private static <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue()))); return column;
    }

    private boolean confirm(String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner); DialogThemeHelper.applyTheme(alert); alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void show(Alert.AlertType type, String text) {
        Alert alert = new Alert(type, text, ButtonType.OK); alert.initOwner(owner);
        DialogThemeHelper.applyTheme(alert); alert.setHeaderText(null); alert.showAndWait();
    }

    private static List<String> lines(String text) {
        return text == null ? List.of() : text.lines().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024d * 1024d));
    }

    private static String previewStateText(RagKnowledgeStorePresentation.PreviewState state) {
        return switch (state) {
            case WILL_INDEX -> I18n.get("ai.rag.preview.willIndex");
            case UNCHANGED -> I18n.get("ai.rag.preview.unchanged");
            case SKIPPED -> I18n.get("ai.rag.preview.skipped");
        };
    }

    private static String message(Throwable error) {
        return error.getMessage() != null && !error.getMessage().isBlank() ? error.getMessage() : error.getClass().getSimpleName();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record StoreDraft(
        String name, RagStoreType type, String embeddingModelId, int dimensions,
        boolean text, boolean coding, boolean autonomous,
        String endpoint, String collection, String apiKey) { }

}
