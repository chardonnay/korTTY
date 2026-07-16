package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ai.huggingface.HuggingFaceClient;
import de.kortty.ai.huggingface.HuggingFaceDownloadPlan;
import de.kortty.ai.huggingface.HuggingFaceDownloadProgress;
import de.kortty.ai.huggingface.HuggingFaceDownloadResult;
import de.kortty.ai.huggingface.HuggingFaceDownloadTask;
import de.kortty.ai.huggingface.HuggingFaceHardwareEstimator;
import de.kortty.ai.huggingface.HuggingFaceModel;
import de.kortty.ai.huggingface.HuggingFaceModelCatalog;
import de.kortty.ai.huggingface.HuggingFaceModelDownloader;
import de.kortty.ai.huggingface.HuggingFaceModelFile;
import de.kortty.ai.huggingface.HuggingFaceTokenProvider;
import de.kortty.ai.llama.EmbeddedLlamaAiService;
import de.kortty.ai.llama.EmbeddedLlamaEmbeddingService;
import de.kortty.ai.llama.GgufMetadataReader;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaMemoryEstimator;
import de.kortty.ai.llama.LlamaModelPurpose;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.llama.LlamaRuntimeManager;
import de.kortty.ai.llama.LlamaRuntimeBackendCompatibility;
import de.kortty.ai.llama.LlamaRuntimeState;
import de.kortty.ai.runtimeupdate.LlamaRuntimeInstallation;
import de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.GlobalSettings;
import de.kortty.rag.CancellationToken;
import de.kortty.security.EncryptionService;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Installed-model table, Hugging Face browser and resumable download controls. */
final class LocalModelManagerPane extends VBox {

    private static final long GIB = 1024L * 1024L * 1024L;

    private final KorTTYApplication app;
    private final Window owner;
    private final Runnable modelsChanged;
    private final Path llmDirectory;
    private final LlamaModelRegistry registry;
    private final LlamaRuntimeUpdateCoordinator runtimeCoordinator;
    private final ObservableList<LlamaModel> installedModels = FXCollections.observableArrayList();
    private final ObservableList<HuggingFaceModel> hubModels = FXCollections.observableArrayList();
    private final Map<String, CompletableFuture<HuggingFaceModel>> hubDetailRequests = new ConcurrentHashMap<>();
    private final Set<String> hubDetailsLoading = ConcurrentHashMap.newKeySet();
    private final TableView<LlamaModel> installedTable = new TableView<>(installedModels);
    private final TableView<HuggingFaceModel> hubTable = new TableView<>(hubModels);
    private final TextField hubQuery = new TextField();
    private final ComboBox<String> quantization = new ComboBox<>();
    private final Label runtimeLabel = new Label();
    private final Label status = new Label();
    private final VBox downloadStatusPanel = new VBox(6);
    private final Label downloadModelDetails = new Label();
    private final Label downloadFileDetails = new Label();
    private final Label downloadAmountDetails = new Label();
    private final Label downloadTimingDetails = new Label();
    private final ProgressBar downloadProgress = new ProgressBar(0);
    private final Button downloadModel = new Button();
    private final Button pauseDownload = new Button();
    private final Button cancelDownload = new Button();
    private final Button runtimeAction = new Button();
    private final Button loadMoreHubResults = new Button();
    private URI hubNextPage;
    private long hubSearchGeneration;
    private HuggingFaceDownloadTask activeDownload;
    private boolean hubDownloadPreparing;
    private boolean downloadPaused;
    private volatile boolean closed;
    private final AtomicReference<HuggingFaceDownloadProgress> pendingDownloadProgress = new AtomicReference<>();
    private final AtomicBoolean downloadProgressUpdateScheduled = new AtomicBoolean();
    private AutoCloseable runtimeStatusSubscription;

    LocalModelManagerPane(KorTTYApplication app, Window owner, Runnable modelsChanged) {
        this.app = app;
        this.owner = owner;
        this.modelsChanged = modelsChanged != null ? modelsChanged : () -> { };
        this.llmDirectory = KorTTYApplication.getConfigDirectory().resolve("llm");
        this.registry = LlamaModelRegistry.inDirectory(llmDirectory);
        this.runtimeCoordinator = LlamaRuntimeUpdateCoordinator.getDefault();

        setSpacing(10);
        setPadding(new Insets(8));
        Label intro = new Label(I18n.get("ai.local.models.intro"));
        intro.setWrapText(true);
        runtimeLabel.setWrapText(true);
        status.setWrapText(true);
        status.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");

        configureInstalledTable();
        configureHubTable();
        VBox installed = buildInstalledSection();
        VBox hub = buildHubSection();
        buildDownloadStatusPanel();
        VBox.setVgrow(installedTable, Priority.ALWAYS);
        VBox.setVgrow(hubTable, Priority.ALWAYS);
        getChildren().addAll(intro, runtimeLabel, installed, hub, status, downloadStatusPanel);
        VBox.setVgrow(installed, Priority.ALWAYS);
        VBox.setVgrow(hub, Priority.ALWAYS);
        runtimeStatusSubscription = runtimeCoordinator.addListener(update -> Platform.runLater(() -> {
            updateRuntimeLabel();
            renderRuntimeStatus(update);
            if (update.state() == LlamaRuntimeUpdateCoordinator.State.READY
                || update.state() == LlamaRuntimeUpdateCoordinator.State.PENDING_FIRST_LAUNCH
                || update.state() == LlamaRuntimeUpdateCoordinator.State.REVOKED) {
                try {
                    registry.reload();
                    installedModels.setAll(registry.list());
                    installedTable.refresh();
                    modelsChanged.run();
                } catch (RuntimeException error) {
                    status.setText(message(error));
                }
            }
        }));
        refresh();
    }

    void refresh() {
        try {
            registry.reload();
            installedModels.setAll(registry.list());
            updateRuntimeLabel();
            installedTable.refresh();
            renderRuntimeStatus(runtimeCoordinator.status());
        } catch (RuntimeException error) {
            status.setText(message(error));
        }
    }

    private VBox buildInstalledSection() {
        Label title = sectionTitle(I18n.get("ai.local.models.installed"));
        runtimeAction.setText(I18n.get("ai.local.models.runtime.install"));
        runtimeAction.setOnAction(event -> installOrUpdateRuntime());
        Button wizard = new Button(I18n.get("ai.local.models.setupWizard"));
        wizard.setOnAction(event -> openSetupWizard());
        Button importModel = new Button(I18n.get("ai.local.models.import"));
        importModel.setOnAction(event -> importGguf());
        Button configure = new Button(I18n.get("ai.local.models.configure"));
        configure.setOnAction(event -> configureSelected());
        Button start = new Button(I18n.get("ai.local.models.start"));
        start.setOnAction(event -> startSelected());
        Button stop = new Button(I18n.get("ai.local.models.stop"));
        stop.setOnAction(event -> stopSelected());
        Button remove = new Button(I18n.get("ai.local.models.remove"));
        remove.setOnAction(event -> removeSelected());
        Button refreshButton = new Button(I18n.get("ai.manager.refresh"));
        refreshButton.setOnAction(event -> refresh());
        HBox buttons = new HBox(8, runtimeAction, wizard, importModel, configure, start, stop, remove, refreshButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(7, title, installedTable, buttons);
        box.minHeightProperty().bind(Bindings.when(downloadStatusPanel.visibleProperty())
            .then(155d)
            .otherwise(210d));
        return box;
    }

    private VBox buildHubSection() {
        Label title = sectionTitle(I18n.get("ai.local.models.hub"));
        hubQuery.setPromptText(I18n.get("ai.local.models.search.prompt"));
        hubQuery.setOnAction(event -> searchHub());
        Button search = new Button(I18n.get("ai.local.models.search"));
        search.setOnAction(event -> searchHub());
        loadMoreHubResults.setText(I18n.get("ai.local.models.search.more"));
        loadMoreHubResults.setDisable(true);
        loadMoreHubResults.setOnAction(event -> loadHubPage(true));
        HBox searchBox = new HBox(8, hubQuery, search, loadMoreHubResults);
        HBox.setHgrow(hubQuery, Priority.ALWAYS);

        quantization.setPromptText(I18n.get("ai.local.models.quantization"));
        quantization.setPrefWidth(160);
        downloadModel.setText(I18n.get("ai.local.models.download"));
        downloadModel.setOnAction(event -> downloadSelected());
        pauseDownload.setText(I18n.get("ai.local.models.pause"));
        pauseDownload.setDisable(true);
        pauseDownload.setOnAction(event -> togglePause());
        cancelDownload.setText(I18n.get("ai.local.models.cancel"));
        cancelDownload.setDisable(true);
        cancelDownload.setOnAction(event -> cancelDownload());
        HBox actions = new HBox(8, new Label(I18n.get("ai.local.models.quantization")), quantization,
            downloadModel);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(7, title, searchBox, hubTable, actions);
        box.minHeightProperty().bind(Bindings.when(downloadStatusPanel.visibleProperty())
            .then(175d)
            .otherwise(230d));
        return box;
    }

    private void buildDownloadStatusPanel() {
        Label title = sectionTitle(I18n.get("ai.local.models.download.panel.title"));
        downloadModelDetails.setWrapText(true);
        downloadModelDetails.setStyle("-fx-font-weight: bold;");
        downloadFileDetails.setWrapText(true);
        downloadAmountDetails.setWrapText(true);
        downloadTimingDetails.setWrapText(true);
        downloadProgress.setMaxWidth(Double.MAX_VALUE);
        downloadProgress.setPrefWidth(640);
        downloadProgress.setMinHeight(10);
        downloadProgress.setPrefHeight(10);
        HBox controls = new HBox(8, pauseDownload, cancelDownload);
        controls.setAlignment(Pos.CENTER_LEFT);
        downloadStatusPanel.setSpacing(5);
        downloadStatusPanel.setPadding(new Insets(8));
        downloadStatusPanel.setStyle(
            "-fx-border-color: -fx-box-border; -fx-border-radius: 4; -fx-background-radius: 4;");
        downloadStatusPanel.getChildren().addAll(
            title,
            downloadModelDetails,
            downloadFileDetails,
            downloadProgress,
            downloadAmountDetails,
            downloadTimingDetails,
            controls);
        downloadStatusPanel.setVisible(false);
        downloadStatusPanel.managedProperty().bind(downloadStatusPanel.visibleProperty());
    }

    private void configureInstalledTable() {
        installedTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        installedTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        installedTable.setPlaceholder(new Label(I18n.get("ai.local.models.empty")));

        TableColumn<LlamaModel, String> name = column(I18n.get("ai.local.models.column.name"),
            model -> model.getDisplayName());
        TableColumn<LlamaModel, String> file = column(I18n.get("ai.local.models.column.file"),
            model -> model.getModelPath().getFileName().toString());
        TableColumn<LlamaModel, String> backend = column(I18n.get("ai.local.models.column.backend"),
            model -> model.getBackend().name());
        TableColumn<LlamaModel, String> purpose = column(I18n.get("ai.local.models.column.purpose"),
            model -> I18n.get("ai.local.models.purpose." + model.getPurpose().name().toLowerCase(Locale.ROOT)));
        TableColumn<LlamaModel, String> idle = column(I18n.get("ai.local.models.column.idle"),
            model -> model.getIdleTimeoutMinutes() == 0
                ? I18n.get("ai.local.models.never")
                : I18n.get("ai.local.models.minutes", model.getIdleTimeoutMinutes()));
        TableColumn<LlamaModel, String> state = column(I18n.get("ai.local.models.column.state"), this::runtimeState);
        name.setMinWidth(170);
        file.setMinWidth(180);
        installedTable.getColumns().addAll(List.of(name, file, purpose, backend, idle, state));
    }

    private void configureHubTable() {
        hubTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        hubTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        hubTable.setPlaceholder(new Label(I18n.get("ai.local.models.search.empty")));
        hubTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, model) -> {
            String preferred = sameHubModel(oldValue, model) ? quantization.getValue() : null;
            updateHubQuantizations(model, preferred);
            hubTable.refresh();
            loadSelectedHubDetails(model);
        });
        quantization.valueProperty().addListener((obs, oldValue, value) -> hubTable.refresh());

        TableColumn<HuggingFaceModel, String> model = column(I18n.get("ai.local.models.column.model"), HuggingFaceModel::id);
        TableColumn<HuggingFaceModel, String> architecture = column(I18n.get("ai.local.models.column.architecture"),
            value -> fallback(value.architecture()));
        TableColumn<HuggingFaceModel, String> quants = column(I18n.get("ai.local.models.column.quantizations"),
            value -> String.join(", ", value.quantizations()));
        TableColumn<HuggingFaceModel, String> license = column(I18n.get("ai.local.models.column.license"),
            value -> fallback(value.license()));
        TableColumn<HuggingFaceModel, String> size = column(I18n.get("ai.local.models.column.size"),
            value -> hubDetailsLoading.contains(hubModelKey(value))
                ? I18n.get("ai.local.models.details.loading")
                : formatBytes(displayQuantizationBytes(value)));
        TableColumn<HuggingFaceModel, String> context = column(I18n.get("ai.local.models.column.context"),
            value -> value.contextLength() > 0 ? Long.toString(value.contextLength()) : "—");
        TableColumn<HuggingFaceModel, String> fit = column(I18n.get("ai.local.models.column.hardware"),
            value -> hubDetailsLoading.contains(hubModelKey(value))
                ? I18n.get("ai.local.models.details.loading")
                : I18n.get("ai.local.models.hardware." + HuggingFaceHardwareEstimator
                    .estimate(displayQuantizationBytes(value), detectedMemory()).suitability()
                    .name().toLowerCase(Locale.ROOT)));
        model.setMinWidth(235);
        quants.setMinWidth(150);
        hubTable.getColumns().addAll(List.of(model, architecture, quants, license, size, context, fit));
    }

    private void searchHub() {
        hubSearchGeneration++;
        hubNextPage = null;
        hubModels.clear();
        loadHubPage(false);
    }

    private void loadHubPage(boolean continuation) {
        long generation = hubSearchGeneration;
        URI next = continuation ? hubNextPage : null;
        if (continuation && next == null) {
            return;
        }
        status.setText(I18n.get("ai.local.models.search.running"));
        hubTable.setDisable(true);
        loadMoreHubResults.setDisable(true);
        HuggingFaceClient client = new HuggingFaceClient(tokenProvider());
        CompletableFuture.supplyAsync(() -> {
            try {
                return next != null
                    ? client.continueGgufModelSearch(next)
                    : client.searchGgufModelsPage(hubQuery.getText(), 50);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }).whenComplete((page, error) -> Platform.runLater(() -> {
            if (generation != hubSearchGeneration) {
                return;
            }
            hubTable.setDisable(false);
            if (error != null) {
                status.setText(I18n.get("ai.local.models.search.failed") + ": " + message(rootCause(error)));
                loadMoreHubResults.setDisable(hubNextPage == null);
                return;
            }
            LinkedHashMap<String, HuggingFaceModel> merged = new LinkedHashMap<>();
            hubModels.forEach(model -> merged.put(model.id(), model));
            page.models().forEach(model -> merged.putIfAbsent(model.id(), model));
            hubModels.setAll(merged.values());
            hubNextPage = page.nextPage().orElse(null);
            loadMoreHubResults.setDisable(hubNextPage == null);
            status.setText(I18n.get("ai.local.models.search.results", hubModels.size()));
            if (!continuation && !hubModels.isEmpty()) {
                hubTable.getSelectionModel().selectFirst();
            }
        }));
    }

    private void updateHubQuantizations(HuggingFaceModel model, String preferred) {
        List<String> values = model != null
            ? model.quantizations().stream().sorted(Comparator.naturalOrder()).toList()
            : List.of();
        quantization.getItems().setAll(values);
        quantization.setValue(preferredQuantization(model, preferred));
    }

    private void loadSelectedHubDetails(HuggingFaceModel summary) {
        if (summary == null || !sameHubModel(summary, hubTable.getSelectionModel().getSelectedItem())) {
            return;
        }
        String selectedQuantization = quantization.getValue();
        if (selectedQuantization == null || summary.hasExactFileSizes(selectedQuantization)) {
            return;
        }
        long generation = hubSearchGeneration;
        String key = hubModelKey(summary);
        CompletableFuture<HuggingFaceModel> request = hubDetailsFuture(summary);
        hubTable.refresh();
        request.whenComplete((detailed, error) -> Platform.runLater(() -> {
            hubDetailsLoading.remove(key);
            if (error != null) {
                hubDetailRequests.remove(key, request);
                if (generation == hubSearchGeneration
                    && sameHubModel(summary, hubTable.getSelectionModel().getSelectedItem())) {
                    status.setText(I18n.get("ai.local.models.details.failed", summary.id(),
                        message(rootCause(error))));
                }
                hubTable.refresh();
                return;
            }
            applyHubDetails(summary, detailed, generation, selectedQuantization);
        }));
    }

    private CompletableFuture<HuggingFaceModel> hubDetailsFuture(HuggingFaceModel summary) {
        String key = hubModelKey(summary);
        return hubDetailRequests.computeIfAbsent(key, ignored -> {
            hubDetailsLoading.add(key);
            return CompletableFuture.supplyAsync(() -> {
                try {
                    HuggingFaceClient client = new HuggingFaceClient(tokenProvider());
                    return summary.hasPinnedRevision()
                        ? client.getModel(summary.id(), summary.revision())
                        : client.getModel(summary.id());
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            });
        });
    }

    private void applyHubDetails(
        HuggingFaceModel summary,
        HuggingFaceModel detailed,
        long generation,
        String preferredQuantization
    ) {
        if (generation != hubSearchGeneration || detailed == null || !sameHubModel(summary, detailed)) {
            return;
        }
        int index = -1;
        for (int candidate = 0; candidate < hubModels.size(); candidate++) {
            if (sameHubModel(summary, hubModels.get(candidate))) {
                index = candidate;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        boolean selected = sameHubModel(summary, hubTable.getSelectionModel().getSelectedItem());
        String quantizationToKeep = selected && quantization.getValue() != null
            ? quantization.getValue()
            : preferredQuantization;
        hubModels.set(index, detailed);
        if (selected) {
            hubTable.getSelectionModel().select(index);
            updateHubQuantizations(detailed, quantizationToKeep);
        }
        hubTable.refresh();
    }

    private void downloadSelected() {
        HuggingFaceModel selected = hubTable.getSelectionModel().getSelectedItem();
        String selectedQuantization = quantization.getValue();
        if (selected == null || selectedQuantization == null) {
            show(Alert.AlertType.WARNING, I18n.get("ai.local.models.download.select"));
            return;
        }
        if (hubDownloadPreparing || activeDownload != null) {
            return;
        }
        hubDownloadPreparing = true;
        downloadModel.setDisable(true);
        hubTable.setDisable(true);
        quantization.setDisable(true);
        status.setText(I18n.get("ai.local.models.search.running"));
        long generation = hubSearchGeneration;
        CompletableFuture<HuggingFaceModel> details = hasVerifiedDownloadMetadata(selected, selectedQuantization)
            ? CompletableFuture.completedFuture(selected)
            : hubDetailsFuture(selected);
        details.whenComplete((detailed, error) -> Platform.runLater(() -> {
            if (closed || generation != hubSearchGeneration) {
                return;
            }
            hubDownloadPreparing = false;
            downloadModel.setDisable(false);
            hubDetailsLoading.remove(hubModelKey(selected));
            hubTable.setDisable(false);
            quantization.setDisable(false);
            if (error != null) {
                hubDetailRequests.remove(hubModelKey(selected), details);
                status.setText(I18n.get("ai.local.models.search.failed") + ": " + message(rootCause(error)));
                hubTable.refresh();
                return;
            }
            applyHubDetails(selected, detailed, generation, selectedQuantization);
            beginVerifiedDownload(detailed, selectedQuantization);
        }));
    }

    private void beginVerifiedDownload(HuggingFaceModel selected, String selectedQuantization) {
        if (closed) {
            return;
        }
        if (!selected.hasPinnedRevision()) {
            show(Alert.AlertType.ERROR, I18n.get("ai.local.models.download.unpinned"));
            return;
        }
        long downloadBytes = selected.bytesForQuantization(selectedQuantization);
        if (downloadBytes <= 0 || !hasVerifiedDownloadMetadata(selected, selectedQuantization)) {
            show(Alert.AlertType.ERROR, I18n.get("ai.local.models.download.metadataMissing"));
            return;
        }
        if (selected.license() == null || selected.license().isBlank()) {
            if (!confirm(I18n.get("ai.local.models.license.unknown"))) {
                return;
            }
        } else if (!confirm(I18n.get("ai.local.models.license.confirm", selected.license(),
            formatBytes(downloadBytes)))) {
            return;
        }
        ensureRuntimeAvailable(() -> startVerifiedDownload(selected, selectedQuantization));
    }

    private void startVerifiedDownload(HuggingFaceModel selected, String selectedQuantization) {
        if (closed) {
            return;
        }
        Path target = llmDirectory.resolve("models")
            .resolve(safeId(selected.id() + "-" + selectedQuantization + "-" + selected.revision().substring(0, 12)));
        HuggingFaceDownloadPlan plan;
        try {
            plan = HuggingFaceDownloadPlan.forQuantization(selected, selectedQuantization, target);
        } catch (RuntimeException error) {
            show(Alert.AlertType.ERROR, message(error));
            return;
        }
        prepareDownloadStatus(selected, selectedQuantization, plan.totalBytes());
        activeDownload = new HuggingFaceModelDownloader(tokenProvider()).downloadAsync(plan,
            this::queueDownloadProgress);
        downloadModel.setDisable(true);
        pauseDownload.setDisable(false);
        cancelDownload.setDisable(false);
        downloadPaused = false;
        activeDownload.completion().whenComplete((result, error) -> Platform.runLater(() -> {
            HuggingFaceDownloadProgress latest = pendingDownloadProgress.getAndSet(null);
            if (latest != null) {
                showDownloadProgress(latest);
            }
            pauseDownload.setDisable(true);
            cancelDownload.setDisable(true);
            downloadModel.setDisable(false);
            downloadPaused = false;
            activeDownload = null;
            if (error != null) {
                Throwable cause = rootCause(error);
                if (cause instanceof CancellationException) {
                    downloadFileDetails.setText(I18n.get("ai.local.models.download.cancelled"));
                    status.setText(I18n.get("ai.local.models.download.cancelled"));
                } else {
                    String failure = I18n.get("ai.local.models.download.failed") + ": " + message(cause);
                    downloadFileDetails.setText(failure);
                    status.setText(failure);
                }
                return;
            }
            try {
                Path server = requireRuntimeExecutable();
                LlamaModel model = registerDownloadedModel(
                    selected, selectedQuantization, result, server, Set.of());
                refresh();
                modelsChanged.run();
                runFunctionTest(model);
            } catch (Exception registrationError) {
                status.setText(I18n.get("ai.local.models.download.registerFailed") + ": " + message(registrationError));
            }
        }));
    }

    private void prepareDownloadStatus(
        HuggingFaceModel model,
        String selectedQuantization,
        long totalBytes
    ) {
        prepareDownloadStatus(model.id(), selectedQuantization, totalBytes);
    }

    void prepareDownloadStatus(String modelId, String selectedQuantization, long totalBytes) {
        pendingDownloadProgress.set(null);
        downloadPaused = false;
        downloadModel.setDisable(true);
        pauseDownload.setDisable(false);
        cancelDownload.setDisable(false);
        pauseDownload.setText(I18n.get("ai.local.models.pause"));
        downloadModelDetails.setText(I18n.get(
            "ai.local.models.download.panel.model",
            modelId, selectedQuantization));
        downloadFileDetails.setText(I18n.get("ai.local.models.download.panel.preparing"));
        downloadProgress.setProgress(0);
        downloadAmountDetails.setText(I18n.get(
            "ai.local.models.download.panel.amount", formatBytes(0), formatBytes(totalBytes)));
        downloadTimingDetails.setText(I18n.get(
            "ai.local.models.download.panel.metrics", formatDuration(Duration.ZERO), "—", "—"));
        downloadStatusPanel.setVisible(true);
    }

    /** Runs the final assistant step against the actual local chat or embedding route. */
    private void runFunctionTest(LlamaModel model) {
        status.setText(I18n.get("ai.local.models.functionTest.running", model.getDisplayName()));
        CompletableFuture.runAsync(() -> {
            try {
                functionTest(model);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            installedTable.refresh();
            if (error == null) {
                status.setText(I18n.get("ai.local.models.functionTest.passed", model.getDisplayName()));
            } else {
                status.setText(I18n.get("ai.local.models.functionTest.failed",
                    model.getDisplayName(), message(rootCause(error))));
            }
        }));
    }

    private LlamaModel registerDownloadedModel(
        HuggingFaceModel selected,
        String selectedQuantization,
        HuggingFaceDownloadResult result,
        Path server,
        Set<HuggingFaceModelCatalog.Role> roles
    ) {
        Path primary = result.files().stream().min(Comparator.comparing(Path::toString)).orElseThrow()
            .toAbsolutePath().normalize();
        LlamaModelPurpose expectedPurpose = purposeForRoles(roles);
        Optional<LlamaModel> existing = reusableModelForPurpose(
            registry.list(), primary, expectedPurpose);
        if (existing.isPresent()) {
            return existing.get();
        }
        // A GGUF downloaded earlier through the generic browser may have a CHAT registry entry.
        // Reuse the verified weight file, but create a purpose-specific EMBEDDING entry instead of
        // testing the wrong endpoint or mutating profiles that still rely on the chat entry.
        LlamaModel model = new LlamaModel(
            uniqueModelId(safeId(selected.id() + "-" + selectedQuantization)),
            selected.id() + " " + selectedQuantization,
            primary,
            server,
            LlamaBackend.AUTO,
            expectedPurpose,
            LlamaModel.DEFAULT_CONTEXT_SIZE,
            LlamaModel.AUTO_THREADS,
            LlamaModel.AUTO_GPU_LAYERS,
            LlamaModel.DEFAULT_IDLE_TIMEOUT_MINUTES);
        registry.register(model);
        return model;
    }

    static LlamaModelPurpose purposeForRoles(Set<HuggingFaceModelCatalog.Role> roles) {
        boolean embeddingOnly = roles != null
            && roles.contains(HuggingFaceModelCatalog.Role.EMBEDDING)
            && !roles.contains(HuggingFaceModelCatalog.Role.TEXT)
            && !roles.contains(HuggingFaceModelCatalog.Role.CODING);
        return embeddingOnly ? LlamaModelPurpose.EMBEDDING : LlamaModelPurpose.CHAT;
    }

    static Optional<LlamaModel> reusableModelForPurpose(
        List<LlamaModel> models,
        Path modelPath,
        LlamaModelPurpose purpose
    ) {
        Path normalized = modelPath.toAbsolutePath().normalize();
        return (models != null ? models : List.<LlamaModel>of()).stream()
            .filter(value -> value.getModelPath().toAbsolutePath().normalize().equals(normalized))
            .filter(value -> value.getPurpose() == purpose)
            .findFirst();
    }

    static String preferredQuantization(HuggingFaceModel model, String preferred) {
        if (model == null || model.quantizations().isEmpty()) {
            return null;
        }
        if (preferred != null && model.quantizations().contains(preferred)) {
            return preferred;
        }
        return model.quantizations().contains("Q4_K_M")
            ? "Q4_K_M"
            : model.quantizations().stream().sorted().findFirst().orElse(null);
    }

    static boolean sameHubModel(HuggingFaceModel first, HuggingFaceModel second) {
        if (first == null || second == null || !first.id().equals(second.id())) {
            return false;
        }
        return java.util.Objects.equals(first.revision(), second.revision());
    }

    static boolean hasVerifiedDownloadMetadata(HuggingFaceModel model, String quantization) {
        if (model == null || !model.hasPinnedRevision()) {
            return false;
        }
        List<HuggingFaceModelFile> files = model.filesForQuantization(quantization);
        return !files.isEmpty()
            && files.stream().allMatch(HuggingFaceModelFile::downloadableAndVerifiable);
    }

    private static String hubModelKey(HuggingFaceModel model) {
        if (model == null) {
            return "";
        }
        return model.id() + "\n" + (model.revision() != null ? model.revision() : "<head>");
    }

    private long displayQuantizationBytes(HuggingFaceModel model) {
        if (model == null || model.quantizations().isEmpty()) {
            return -1;
        }
        String selected = model.equals(hubTable.getSelectionModel().getSelectedItem())
            ? quantization.getValue()
            : null;
        if (selected == null || !model.quantizations().contains(selected)) {
            selected = model.quantizations().contains("Q4_K_M")
                ? "Q4_K_M"
                : model.quantizations().stream().sorted().findFirst().orElse(null);
        }
        return model.bytesForQuantization(selected);
    }

    private void functionTest(LlamaModel model) throws Exception {
        if (model.getPurpose() == LlamaModelPurpose.EMBEDDING) {
            int dimensions = GgufMetadataReader.embeddingDimensions(model.getModelPath())
                .orElseThrow(() -> new IOException("GGUF embedding dimensions are unavailable."));
            new EmbeddedLlamaEmbeddingService(model.getId(), dimensions)
                .embedQuery("korTTY local embedding function test", CancellationToken.NONE);
        } else {
            new EmbeddedLlamaAiService(
                model.getId(),
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled())
                .executePrompt(
                    "Answer with a single short word.",
                    "Confirm that the local model is ready.");
        }
    }

    private void queueDownloadProgress(HuggingFaceDownloadProgress progress) {
        if (closed) {
            return;
        }
        pendingDownloadProgress.set(progress);
        scheduleDownloadProgressUpdate();
    }

    private void scheduleDownloadProgressUpdate() {
        if (!downloadProgressUpdateScheduled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.delayedExecutor(125, TimeUnit.MILLISECONDS).execute(() -> {
            if (closed) {
                downloadProgressUpdateScheduled.set(false);
                pendingDownloadProgress.set(null);
                return;
            }
            try {
                Platform.runLater(() -> {
                    downloadProgressUpdateScheduled.set(false);
                    HuggingFaceDownloadProgress latest = pendingDownloadProgress.getAndSet(null);
                    if (latest != null) {
                        showDownloadProgress(latest);
                    }
                    if (pendingDownloadProgress.get() != null) {
                        scheduleDownloadProgressUpdate();
                    }
                });
            } catch (IllegalStateException ignored) {
                downloadProgressUpdateScheduled.set(false);
                pendingDownloadProgress.set(null);
            }
        });
    }

    void showDownloadProgress(HuggingFaceDownloadProgress progress) {
        downloadStatusPanel.setVisible(true);
        downloadProgress.setProgress(progress.fraction());
        String phase = I18n.get("ai.local.models.download.phase."
            + progress.phase().name().toLowerCase(Locale.ROOT));
        String speed = formatTransferRate(progress.bytesPerSecond());
        String elapsed = progress.elapsed() != null ? formatDuration(progress.elapsed()) : "—";
        String eta = progress.estimatedRemaining() != null ? formatDuration(progress.estimatedRemaining()) : "—";
        String file = progress.file() != null && !progress.file().isBlank() ? progress.file() : "—";
        downloadFileDetails.setText(I18n.get(
            "ai.local.models.download.panel.file",
            phase, file, progress.fileIndex(), progress.fileCount()));
        downloadAmountDetails.setText(I18n.get(
            "ai.local.models.download.panel.amount",
            formatBytes(progress.downloadedBytes()), formatBytes(progress.totalBytes())));
        downloadTimingDetails.setText(I18n.get(
            "ai.local.models.download.panel.metrics", elapsed, speed, eta));
        if (progress.phase() == HuggingFaceDownloadProgress.Phase.PAUSED) {
            downloadPaused = true;
            pauseDownload.setText(I18n.get("ai.local.models.resume"));
        } else if (progress.phase() == HuggingFaceDownloadProgress.Phase.DOWNLOADING) {
            downloadPaused = false;
            pauseDownload.setText(I18n.get("ai.local.models.pause"));
        }
    }

    private void togglePause() {
        if (activeDownload == null) {
            return;
        }
        downloadPaused = !downloadPaused;
        if (downloadPaused) {
            activeDownload.pause();
            pauseDownload.setText(I18n.get("ai.local.models.resume"));
        } else {
            activeDownload.resume();
            pauseDownload.setText(I18n.get("ai.local.models.pause"));
        }
    }

    private void cancelDownload() {
        if (activeDownload != null && confirm(I18n.get("ai.local.models.cancel.confirm"))) {
            activeDownload.cancel();
        }
    }

    private void importGguf() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("ai.local.models.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            I18n.get("ai.local.models.ggufFiles"), "*.gguf", "*.GGUF"));
        List<java.io.File> selected = chooser.showOpenMultipleDialog(owner);
        if (selected == null || selected.isEmpty()) {
            return;
        }
        ChoiceDialog<String> mode = new ChoiceDialog<>(I18n.get("ai.local.models.import.copy"),
            I18n.get("ai.local.models.import.copy"), I18n.get("ai.local.models.import.reference"));
        mode.initOwner(owner);
        DialogThemeHelper.applyTheme(mode);
        mode.setTitle(I18n.get("ai.local.models.import"));
        mode.setHeaderText(I18n.get("ai.local.models.import.mode"));
        Optional<String> choice = mode.showAndWait();
        if (choice.isEmpty()) {
            return;
        }
        boolean managedCopy = choice.get().equals(I18n.get("ai.local.models.import.copy"));
        ensureRuntimeAvailable(() -> completeImport(selected, managedCopy));
    }

    private void completeImport(List<java.io.File> selected, boolean managedCopy) {
        List<java.io.File> files = List.copyOf(selected);
        status.setText(I18n.get("ai.local.models.import.preparing", files.size()));
        CompletableFuture.supplyAsync(() -> {
            List<PreparedImport> prepared = new ArrayList<>();
            try {
                Path server = requireRuntimeExecutable();
                for (java.io.File file : files) {
                    Path source = file.toPath().toRealPath();
                    Path modelPath = managedCopy ? copyIntoManagedStorage(source) : source;
                    prepared.add(new PreparedImport(
                        modelPath,
                        stripGguf(modelPath.getFileName().toString()),
                        server,
                        managedCopy));
                }
                return List.copyOf(prepared);
            } catch (Exception error) {
                if (managedCopy) {
                    cleanupPreparedImports(prepared);
                }
                throw new CompletionException(error);
            }
        }).whenComplete((prepared, error) -> Platform.runLater(() -> {
            if (error != null) {
                String detail = message(rootCause(error));
                status.setText(I18n.get("ai.local.models.import.failed", detail));
                show(Alert.AlertType.ERROR, detail);
                return;
            }
            int registered = 0;
            try {
                for (PreparedImport item : prepared) {
                    LlamaModel model = editModel(new LlamaModel(
                        uniqueModelId(safeId(item.baseName())),
                        item.baseName(),
                        item.modelPath(),
                        item.server()));
                    if (model != null) {
                        registry.register(model);
                        registered++;
                    }
                }
                refresh();
                modelsChanged.run();
                status.setText(I18n.get("ai.local.models.import.complete", registered));
            } catch (RuntimeException registrationError) {
                status.setText(I18n.get("ai.local.models.import.failed", message(registrationError)));
                show(Alert.AlertType.ERROR, message(registrationError));
            }
        }));
    }

    private static void cleanupPreparedImports(List<PreparedImport> prepared) {
        for (PreparedImport item : prepared) {
            if (!item.managedCopy()) {
                continue;
            }
            try {
                Files.deleteIfExists(item.modelPath());
                Files.deleteIfExists(item.modelPath().getParent());
            } catch (IOException ignored) {
                // A verified orphaned copy is harmless and can be imported or removed manually later.
            }
        }
    }

    private record PreparedImport(Path modelPath, String baseName, Path server, boolean managedCopy) {
    }

    private void configureSelected() {
        LlamaModel selected = installedTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        LlamaModel edited = editModel(selected);
        if (edited != null) {
            try {
                if (!LlamaRuntimeManager.getDefault().stop(selected.getId())) {
                    throw new IllegalStateException(
                        I18n.get("ai.local.models.configure.busy", selected.getDisplayName()));
                }
                registry.register(edited);
                refresh();
                modelsChanged.run();
            } catch (RuntimeException error) {
                show(Alert.AlertType.ERROR, message(error));
            }
        }
    }

    private LlamaModel editModel(LlamaModel original) {
        Dialog<LlamaModel> dialog = new Dialog<>();
        dialog.initOwner(owner);
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("ai.local.models.configure"));
        dialog.setHeaderText(I18n.get("ai.local.models.configure.header"));
        ButtonType save = new ButtonType(I18n.get("settings.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField name = new TextField(original.getDisplayName());
        ComboBox<LlamaBackend> backend = new ComboBox<>(FXCollections.observableArrayList(LlamaBackend.values()));
        backend.setValue(original.getBackend());
        ComboBox<LlamaModelPurpose> purpose = new ComboBox<>(FXCollections.observableArrayList(LlamaModelPurpose.values()));
        purpose.setValue(original.getPurpose());
        Spinner<Integer> context = new Spinner<>(512, 2_097_152,
            original.getContextSize() > 0 ? original.getContextSize() : LlamaModel.DEFAULT_CONTEXT_SIZE, 512);
        context.setEditable(true);
        Spinner<Integer> threads = new Spinner<>(0, 1024, original.getThreadCount());
        threads.setEditable(true);
        Spinner<Integer> gpuLayers = new Spinner<>(-1, 10_000, original.getGpuLayers());
        gpuLayers.setEditable(true);
        CheckBox never = new CheckBox(I18n.get("ai.local.models.never"));
        never.setSelected(original.getIdleTimeoutMinutes() == 0);
        Spinner<Integer> idleMinutes = new Spinner<>(1, 1440,
            original.getIdleTimeoutMinutes() > 0 ? original.getIdleTimeoutMinutes() : 10);
        idleMinutes.setEditable(true);
        idleMinutes.disableProperty().bind(never.selectedProperty());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(9);
        grid.setPadding(new Insets(8));
        int row = 0;
        grid.add(new Label(I18n.get("ai.local.models.column.name")), 0, row);
        grid.add(name, 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.column.file")), 0, row);
        grid.add(new Label(original.getModelPath().toString()), 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.column.backend")), 0, row);
        grid.add(backend, 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.column.purpose")), 0, row);
        grid.add(purpose, 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.context")), 0, row);
        grid.add(context, 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.threads")), 0, row);
        grid.add(threads, 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.gpuLayers")), 0, row);
        grid.add(gpuLayers, 1, row++);
        grid.add(new Label(I18n.get("ai.local.models.unloadAfter")), 0, row);
        grid.add(new HBox(8, idleMinutes, new Label(I18n.get("ai.local.models.minuteUnit")), never), 1, row);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(button -> button == save ? new LlamaModel(
            original.getId(), name.getText(), original.getModelPath(), original.getServerExecutable(),
            backend.getValue(), purpose.getValue(), context.getValue(), threads.getValue(), gpuLayers.getValue(),
            never.isSelected() ? 0 : idleMinutes.getValue()) : null);
        return dialog.showAndWait().orElse(null);
    }

    private void startSelected() {
        List<LlamaModel> selected = List.copyOf(installedTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        List<String> selectedIds = selected.stream().map(LlamaModel::getId).toList();
        Optional<LlamaRuntimeInstallation> activeRuntime = runtimeCoordinator.activeInstallation();
        if (activeRuntime.isEmpty()) {
            if (confirm(I18n.get("ai.local.models.runtime.required.confirm"))) {
                installRuntime(preferredRuntimeBackend(), () -> startModels(selectedIds));
            }
            return;
        }
        LlamaRuntimeBackendCompatibility.Result compatibility =
            LlamaRuntimeBackendCompatibility.evaluate(
                activeRuntime.get().descriptor().backend(), selected);
        if (compatibility.status()
            == LlamaRuntimeBackendCompatibility.Status.CONFLICTING_MODEL_BACKENDS) {
            show(Alert.AlertType.ERROR, I18n.get("ai.local.models.runtime.backend.conflict"));
            return;
        }
        if (compatibility.status()
            == LlamaRuntimeBackendCompatibility.Status.REQUIRES_DIFFERENT_RUNTIME) {
            LlamaBackend required = compatibility.requiredBackend();
            if (confirm(I18n.get("ai.local.models.runtime.backend.mismatch",
                backendLabel(required), backendLabel(activeRuntime.get().descriptor().backend())))) {
                persistPreferredRuntimeBackend(required);
                installRuntime(required, () -> startModels(selectedIds));
            }
            return;
        }
        startModels(selectedIds);
    }

    private void startModels(List<String> selectedIds) {
        registry.reload();
        List<LlamaModel> selected = selectedIds.stream()
            .map(registry::find)
            .flatMap(Optional::stream)
            .toList();
        if (selected.isEmpty()) {
            status.setText(I18n.get("ai.local.models.start.failed"));
            return;
        }
        LlamaRuntimeManager manager = LlamaRuntimeManager.getDefault();
        LlamaMemoryEstimator.Estimate memory = new LlamaMemoryEstimator().estimate(
            selected, registry.list(), manager.statuses(), detectedMemory());
        if (memory.warningRecommended()) {
            int percent = (int) Math.min(999, Math.round(memory.systemMemoryFraction() * 100d));
            if (!confirm(I18n.get("ai.local.models.memory.warning",
                formatBytes(memory.estimatedRuntimeBytes()),
                formatBytes(memory.systemMemoryBytes()),
                percent,
                memory.runtimeCount()))) {
                status.setText(I18n.get("ai.local.models.memory.cancelled"));
                return;
            }
        }
        status.setText(I18n.get("ai.local.models.starting", selected.size()));
        CompletableFuture.allOf(selected.stream().map(model -> CompletableFuture.runAsync(() -> {
            try (LlamaRuntimeManager.RuntimeLease ignored = LlamaRuntimeManager.getDefault().acquire(model.getId())) {
                // Releasing after readiness starts the configured idle countdown without killing the sidecar.
            }
        })).toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> Platform.runLater(() -> {
            installedTable.refresh();
            status.setText(error == null
                ? I18n.get("ai.local.models.started", selected.size())
                : I18n.get("ai.local.models.start.failed") + ": " + message(rootCause(error)));
        }));
    }

    private void stopSelected() {
        int stopped = 0;
        for (LlamaModel model : List.copyOf(installedTable.getSelectionModel().getSelectedItems())) {
            if (LlamaRuntimeManager.getDefault().stop(model.getId())) {
                stopped++;
            }
        }
        installedTable.refresh();
        status.setText(I18n.get("ai.local.models.stopped", stopped));
    }

    private void removeSelected() {
        List<LlamaModel> selected = List.copyOf(installedTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || !confirm(I18n.get("ai.local.models.remove.confirm", selected.size()))) {
            return;
        }
        try {
            for (LlamaModel model : selected) {
                if (!LlamaRuntimeManager.getDefault().stop(model.getId())) {
                    throw new IllegalStateException(I18n.get("ai.local.models.remove.busy", model.getDisplayName()));
                }
                registry.remove(model.getId());
            }
            refresh();
            modelsChanged.run();
        } catch (RuntimeException error) {
            show(Alert.AlertType.ERROR, message(error));
        }
    }

    void openSetupWizard() {
        LocalAiSetupWizardDialog wizard = new LocalAiSetupWizardDialog(
            owner,
            detectedMemory(),
            setupWorkflow());
        wizard.showAndWait();
        refresh();
        modelsChanged.run();
    }

    void openEmbeddingSetupWizard() {
        LocalAiSetupWizardDialog wizard = new LocalAiSetupWizardDialog(
            owner,
            detectedMemory(),
            setupWorkflow(),
            HuggingFaceModelCatalog.Role.EMBEDDING);
        wizard.showAndWait();
        refresh();
        modelsChanged.run();
    }

    private LocalAiSetupWorkflow setupWorkflow() {
        return new LocalAiSetupWorkflow() {
            @Override
            public CompletableFuture<List<ModelDetails>> inspect(
                Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections
            ) {
                return inspectSetupModels(selections);
            }

            @Override
            public Installation install(
                Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections,
                List<ModelDetails> details,
                Consumer<Progress> progressListener
            ) {
                return new SetupInstallation(selections, details, progressListener);
            }
        };
    }

    private CompletableFuture<List<LocalAiSetupWorkflow.ModelDetails>> inspectSetupModels(
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections
    ) {
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> snapshot =
            selections != null ? Map.copyOf(selections) : Map.of();
        return CompletableFuture.supplyAsync(() -> {
            HuggingFaceClient client = new HuggingFaceClient(tokenProvider());
            List<LocalAiSetupWorkflow.ModelDetails> result = new ArrayList<>();
            for (HuggingFaceModelCatalog.Recommendation recommendation : uniqueRecommendations(snapshot)) {
                try {
                    HuggingFaceModel detailed = recommendation.fixedRevision().isPresent()
                        ? client.getModel(recommendation.modelId(), recommendation.fixedRevision().orElseThrow())
                        : client.getModel(recommendation.modelId());
                    if (!detailed.hasPinnedRevision()) {
                        throw new IOException(I18n.get("ai.local.models.download.unpinned"));
                    }
                    List<de.kortty.ai.huggingface.HuggingFaceModelFile> files =
                        detailed.filesForQuantization(recommendation.quantization());
                    if (files.isEmpty()) {
                        throw new IOException(I18n.get(
                            "ai.local.wizard.license.quantizationMissing",
                            recommendation.quantization(), recommendation.modelId()));
                    }
                    long bytes = 0;
                    for (de.kortty.ai.huggingface.HuggingFaceModelFile file : files) {
                        bytes = Math.addExact(bytes, file.size());
                    }
                    result.add(new LocalAiSetupWorkflow.ModelDetails(recommendation, detailed, bytes));
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }
            return List.copyOf(result);
        });
    }

    private static List<HuggingFaceModelCatalog.Recommendation> uniqueRecommendations(
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections
    ) {
        LinkedHashSet<HuggingFaceModelCatalog.Recommendation> unique = new LinkedHashSet<>();
        for (HuggingFaceModelCatalog.Role role : HuggingFaceModelCatalog.Role.values()) {
            HuggingFaceModelCatalog.Recommendation recommendation = selections.get(role);
            if (recommendation != null) {
                if (!recommendation.roles().contains(role)) {
                    throw new IllegalArgumentException("Recommendation does not support role " + role + ".");
                }
                unique.add(recommendation);
            }
        }
        return List.copyOf(unique);
    }

    private final class SetupInstallation implements LocalAiSetupWorkflow.Installation {

        private final Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections;
        private final List<LocalAiSetupWorkflow.ModelDetails> details;
        private final Consumer<LocalAiSetupWorkflow.Progress> progressListener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<List<LlamaModel>> completion;
        private volatile HuggingFaceDownloadTask currentDownload;

        private SetupInstallation(
            Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections,
            List<LocalAiSetupWorkflow.ModelDetails> details,
            Consumer<LocalAiSetupWorkflow.Progress> progressListener
        ) {
            this.selections = selections != null ? Map.copyOf(selections) : Map.of();
            this.details = details != null ? List.copyOf(details) : List.of();
            this.progressListener = progressListener != null ? progressListener : ignored -> { };
            validateInspectionMatchesSelections();
            this.completion = CompletableFuture.supplyAsync(this::run);
        }

        @Override
        public CompletableFuture<List<LlamaModel>> completion() {
            return completion;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            HuggingFaceDownloadTask download = currentDownload;
            if (download != null) {
                download.cancel();
            }
        }

        private List<LlamaModel> run() {
            try {
                checkCancelled();
                ensureSetupRuntime(details.size());
                Path server = requireRuntimeExecutable();
                Map<HuggingFaceModelCatalog.Recommendation, LlamaModel> installed = new LinkedHashMap<>();
                for (int index = 0; index < details.size(); index++) {
                    checkCancelled();
                    LocalAiSetupWorkflow.ModelDetails detail = details.get(index);
                    LlamaModel model = installAndTest(detail, server, index, details.size());
                    installed.put(detail.recommendation(), model);
                }
                checkCancelled();
                publish(LocalAiSetupWorkflow.Phase.SAVING_ROLES, null,
                    details.size(), details.size(), 0.99d, "");
                GlobalSettings settings = requireRoleSettings();
                for (Map.Entry<HuggingFaceModelCatalog.Recommendation, LlamaModel> entry : installed.entrySet()) {
                    assignRoles(settings, entry.getValue(), selectedRoles(entry.getKey()));
                }
                app.getGlobalSettingsManager().save();
                publish(LocalAiSetupWorkflow.Phase.COMPLETE, null,
                    details.size(), details.size(), 1d, "");
                return List.copyOf(new LinkedHashSet<>(installed.values()));
            } catch (CancellationException error) {
                throw error;
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }

        private void validateInspectionMatchesSelections() {
            List<HuggingFaceModelCatalog.Recommendation> expected = uniqueRecommendations(selections);
            List<HuggingFaceModelCatalog.Recommendation> actual = details.stream()
                .map(LocalAiSetupWorkflow.ModelDetails::recommendation).toList();
            if (!expected.equals(actual) || expected.isEmpty()) {
                throw new IllegalArgumentException("Verified model metadata no longer matches the wizard selection.");
            }
        }

        private void ensureSetupRuntime(int totalModels) throws IOException {
            if (findRuntimeExecutable().isPresent()) {
                return;
            }
            publish(LocalAiSetupWorkflow.Phase.RUNTIME, null, 0, totalModels, 0d, "");
            LlamaRuntimeUpdateCoordinator.Status runtime;
            try {
                runtime = runtimeCoordinator.installStable(preferredRuntimeBackend()).join();
            } catch (CompletionException error) {
                Throwable cause = rootCause(error);
                if (cause instanceof CancellationException cancellation) {
                    throw cancellation;
                }
                throw new IOException(message(cause), cause);
            }
            checkCancelled();
            if (runtime.state() != LlamaRuntimeUpdateCoordinator.State.READY
                && findRuntimeExecutable().isEmpty()) {
                String detail = runtime.detail() != null ? runtime.detail() : runtime.state().name();
                throw new IOException(I18n.get("ai.local.models.runtime.install.failed") + ": " + detail);
            }
        }

        private LlamaModel installAndTest(
            LocalAiSetupWorkflow.ModelDetails detail,
            Path server,
            int index,
            int totalModels
        ) throws Exception {
            HuggingFaceModelCatalog.Recommendation recommendation = detail.recommendation();
            HuggingFaceModel selected = detail.model();
            Path target = llmDirectory.resolve("models")
                .resolve(safeId(selected.id() + "-" + recommendation.quantization()
                    + "-" + selected.revision().substring(0, 12)));
            HuggingFaceDownloadPlan plan = HuggingFaceDownloadPlan.forQuantization(
                selected, recommendation.quantization(), target);
            HuggingFaceDownloadTask download = new HuggingFaceModelDownloader(tokenProvider()).downloadAsync(
                plan,
                progress -> publishDownload(progress, recommendation, index, totalModels));
            currentDownload = download;
            HuggingFaceDownloadResult result;
            try {
                result = download.completion().join();
            } catch (CompletionException error) {
                Throwable cause = rootCause(error);
                if (cause instanceof CancellationException) {
                    throw new CancellationException("Local AI setup was cancelled.");
                }
                throw new IOException(message(cause), cause);
            } finally {
                currentDownload = null;
            }
            checkCancelled();
            publish(LocalAiSetupWorkflow.Phase.REGISTERING, recommendation.modelId(),
                index, totalModels, aggregateFraction(index, totalModels, 0.96d), "");
            LlamaModel model = registerDownloadedModel(
                selected,
                recommendation.quantization(),
                result,
                server,
                selectedRoles(recommendation));
            checkCancelled();
            publish(LocalAiSetupWorkflow.Phase.TESTING, recommendation.modelId(),
                index, totalModels, aggregateFraction(index, totalModels, 0.98d), "");
            functionTest(model);
            return model;
        }

        private Set<HuggingFaceModelCatalog.Role> selectedRoles(
            HuggingFaceModelCatalog.Recommendation recommendation
        ) {
            java.util.EnumSet<HuggingFaceModelCatalog.Role> roles =
                java.util.EnumSet.noneOf(HuggingFaceModelCatalog.Role.class);
            selections.forEach((role, selected) -> {
                if (recommendation.equals(selected)) {
                    roles.add(role);
                }
            });
            return Set.copyOf(roles);
        }

        private void publishDownload(
            HuggingFaceDownloadProgress progress,
            HuggingFaceModelCatalog.Recommendation recommendation,
            int index,
            int totalModels
        ) {
            String detail = formatBytes(progress.downloadedBytes()) + " / "
                + formatBytes(progress.totalBytes());
            publish(LocalAiSetupWorkflow.Phase.DOWNLOADING, recommendation.modelId(),
                index, totalModels, aggregateFraction(index, totalModels, progress.fraction()), detail);
        }

        private void publish(
            LocalAiSetupWorkflow.Phase phase,
            String modelId,
            int completedModels,
            int totalModels,
            double fraction,
            String detail
        ) {
            progressListener.accept(new LocalAiSetupWorkflow.Progress(
                phase, modelId, completedModels, totalModels, fraction, detail));
        }

        private void checkCancelled() {
            if (cancelled.get()) {
                throw new CancellationException("Local AI setup was cancelled.");
            }
        }

        private double aggregateFraction(int index, int totalModels, double currentFraction) {
            return totalModels <= 0 ? 0d : (index + currentFraction) / totalModels;
        }
    }

    private GlobalSettings requireRoleSettings() throws IOException {
        if (app == null || app.getGlobalSettingsManager() == null
            || app.getGlobalSettingsManager().getSettings() == null) {
            throw new IOException(I18n.get("ai.local.preferences.save.failed"));
        }
        return app.getGlobalSettingsManager().getSettings();
    }

    private static void assignRoles(
        GlobalSettings settings,
        LlamaModel model,
        Set<HuggingFaceModelCatalog.Role> roles) {

        if (settings == null || model == null || roles == null || roles.isEmpty()) {
            return;
        }
        if (roles.contains(HuggingFaceModelCatalog.Role.EMBEDDING)) {
            settings.setRagEmbeddingModelId(model.getId());
        }
        if (roles.contains(HuggingFaceModelCatalog.Role.TEXT)
            || roles.contains(HuggingFaceModelCatalog.Role.CODING)) {
            AiProfile profile = settings.getAiProfiles().stream()
                .filter(value -> value != null
                    && value.getConnectionMode() == AiConnectionMode.EMBEDDED_LLAMA_CPP
                    && model.getId().equals(value.getEmbeddedModelId()))
                .findFirst()
                .orElseGet(() -> {
                    AiProfile created = new AiProfile();
                    created.setId(UUID.randomUUID().toString());
                    created.setName(I18n.get("ai.local.models.profileName", model.getDisplayName()));
                    created.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
                    created.setEmbeddedModelId(model.getId());
                    created.setPromptPreset(AiPromptPreset.AUTO);
                    settings.getAiProfiles().add(created);
                    return created;
                });
            if (roles.contains(HuggingFaceModelCatalog.Role.TEXT)) {
                settings.setTextAiProfileId(profile.getId());
            }
            if (roles.contains(HuggingFaceModelCatalog.Role.CODING)) {
                settings.setCodingAiProfileId(profile.getId());
            }
            if (settings.getDefaultAiProfileId() == null) {
                settings.setDefaultAiProfileId(profile.getId());
            }
        }
    }

    private Path copyIntoManagedStorage(Path source) throws IOException {
        Path targetDirectory = llmDirectory.resolve("models").resolve(UUID.randomUUID().toString());
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(source.getFileName());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, target);
            }
            return target;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private Path requireRuntimeExecutable() throws IOException {
        return findRuntimeExecutable().orElseThrow(() -> new IOException(
            I18n.get("ai.local.models.runtime.missing.install")));
    }

    private Optional<Path> findRuntimeExecutable() {
        return runtimeCoordinator.activeInstallation()
            .map(LlamaRuntimeInstallation::executable)
            .filter(Files::isRegularFile)
            .filter(path -> isWindows() || Files.isExecutable(path));
    }

    private void updateRuntimeLabel() {
        Optional<Path> runtime = findRuntimeExecutable();
        runtimeLabel.setText(runtime.map(path -> I18n.get("ai.local.models.runtime.ready", path))
            .orElseGet(() -> I18n.get("ai.local.models.runtime.missing")));
        runtimeAction.setText(runtime.isPresent()
            ? I18n.get("ai.local.models.runtime.update")
            : I18n.get("ai.local.models.runtime.install"));
        LlamaRuntimeUpdateCoordinator.State state = runtimeCoordinator.status().state();
        runtimeAction.setDisable(state == LlamaRuntimeUpdateCoordinator.State.CHECKING
            || state == LlamaRuntimeUpdateCoordinator.State.INSTALLING);
    }

    private void installOrUpdateRuntime() {
        boolean missing = runtimeCoordinator.activeInstallation().isEmpty();
        String confirmation = missing
            ? I18n.get("ai.local.models.runtime.install.confirm")
            : I18n.get("ai.local.models.runtime.update.confirm");
        if (!confirm(confirmation)) {
            return;
        }
        installRuntime(preferredRuntimeBackend(), null);
    }

    private void ensureRuntimeAvailable(Runnable continuation) {
        if (runtimeCoordinator.activeInstallation().isPresent()) {
            if (!closed) {
                continuation.run();
            }
            return;
        }
        if (!confirm(I18n.get("ai.local.models.runtime.required.confirm"))) {
            status.setText(I18n.get("ai.local.models.runtime.required"));
            return;
        }
        installRuntime(preferredRuntimeBackend(), continuation);
    }

    private void installRuntime(LlamaBackend backend, Runnable continuation) {
        status.setText(I18n.get("ai.local.models.runtime.installing"));
        runtimeAction.setDisable(true);
        runtimeCoordinator.installStable(backend != null ? backend : preferredRuntimeBackend())
            .whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed) {
                return;
            }
            updateRuntimeLabel();
            if (error != null) {
                status.setText(I18n.get("ai.local.models.runtime.install.failed") + ": " + message(rootCause(error)));
                return;
            }
            renderRuntimeStatus(result);
            if ((result.state() == LlamaRuntimeUpdateCoordinator.State.READY
                    || result.state() == LlamaRuntimeUpdateCoordinator.State.PENDING_FIRST_LAUNCH)
                && continuation != null) {
                continuation.run();
            }
            }));
    }

    private LlamaBackend preferredRuntimeBackend() {
        GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings() : null;
        return settings != null ? settings.getPreferredLlamaRuntimeBackend() : LlamaBackend.AUTO;
    }

    private void persistPreferredRuntimeBackend(LlamaBackend backend) {
        if (app == null || app.getGlobalSettingsManager() == null || backend == null) {
            return;
        }
        try {
            app.getGlobalSettingsManager().getSettings().setPreferredLlamaRuntimeBackend(backend);
            app.getGlobalSettingsManager().save();
        } catch (Exception error) {
            status.setText(I18n.get("ai.local.preferences.save.failed") + ": " + message(error));
        }
    }

    private static String backendLabel(LlamaBackend backend) {
        return I18n.get("ai.local.runtime.backend." + backend.name().toLowerCase(Locale.ROOT));
    }

    private void renderRuntimeStatus(LlamaRuntimeUpdateCoordinator.Status update) {
        if (update == null) {
            return;
        }
        String key = "ai.local.models.runtime.status." + update.state().name().toLowerCase(Locale.ROOT);
        String text = I18n.get(key);
        if (update.availablePackage() != null) {
            text = I18n.get("ai.local.models.runtime.status.withId", text,
                update.availablePackage().runtimeId());
        }
        if (update.detail() != null && !update.detail().isBlank()) {
            text = text + ": " + update.detail();
        }
        status.setText(text);
    }

    private HuggingFaceTokenProvider tokenProvider() {
        return () -> {
            GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null;
            String encrypted = settings != null ? settings.getEncryptedHuggingFaceToken() : null;
            char[] master = app != null && app.getMasterPasswordManager() != null
                ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (encrypted == null || encrypted.isBlank() || master == null) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(new EncryptionService().decryptPassword(encrypted, master));
            } catch (Exception error) {
                throw new IllegalStateException(I18n.get("ai.local.models.token.failed"), error);
            }
        };
    }

    private String runtimeState(LlamaModel model) {
        return LlamaRuntimeManager.getDefault().status(model.getId())
            .map(LlamaRuntimeManager.RuntimeStatus::state)
            .orElse(LlamaRuntimeState.STOPPED)
            .name();
    }

    void close() {
        closed = true;
        hubSearchGeneration++;
        HuggingFaceDownloadTask download = activeDownload;
        if (download != null) {
            download.cancel();
        }
        pendingDownloadProgress.set(null);
        if (runtimeStatusSubscription != null) {
            try {
                runtimeStatusSubscription.close();
            } catch (Exception ignored) {
                // The coordinator owns no pane resources; listener removal is best effort.
            }
            runtimeStatusSubscription = null;
        }
    }

    private String uniqueModelId(String suggested) {
        String candidate = suggested;
        int suffix = 2;
        while (registry.find(candidate).isPresent()) {
            candidate = suggested + "-" + suffix++;
        }
        return candidate;
    }

    private static <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        return label;
    }

    private boolean confirm(String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        DialogThemeHelper.applyTheme(alert);
        alert.setHeaderText(null);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void show(Alert.AlertType type, String text) {
        Alert alert = new Alert(type, text, ButtonType.OK);
        alert.initOwner(owner);
        DialogThemeHelper.applyTheme(alert);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static long detectedMemory() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                .getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (RuntimeException error) {
            return Runtime.getRuntime().maxMemory();
        }
    }

    private static String safeId(String value) {
        String normalized = value != null ? value.replaceAll("[^A-Za-z0-9._-]+", "-") : "model";
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = "model";
        }
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private static String stripGguf(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".gguf") ? name.substring(0, name.length() - 5) : name;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.1f GiB", (double) bytes / GIB);
        }
        return String.format(Locale.ROOT, "%.1f MiB", (double) bytes / (1024 * 1024));
    }

    static String formatTransferRate(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "—";
        }
        if (bytesPerSecond >= GIB) {
            return String.format(Locale.ROOT, "%.1f GiB/s", (double) bytesPerSecond / GIB);
        }
        if (bytesPerSecond >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB/s", (double) bytesPerSecond / (1024L * 1024L));
        }
        if (bytesPerSecond >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB/s", (double) bytesPerSecond / 1024L);
        }
        return bytesPerSecond + " B/s";
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60);
    }

    private static String fallback(String value) {
        return value != null && !value.isBlank() ? value : "—";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable error) {
        return error.getMessage() != null && !error.getMessage().isBlank()
            ? error.getMessage() : error.getClass().getSimpleName();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
