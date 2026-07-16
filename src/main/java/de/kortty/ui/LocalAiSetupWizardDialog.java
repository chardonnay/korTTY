package de.kortty.ui;

import de.kortty.ai.huggingface.HuggingFaceModelCatalog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Beginner flow for selecting, downloading, assigning and testing local models. */
final class LocalAiSetupWizardDialog extends Dialog<Void> {

    private final long systemMemoryBytes;
    private final LocalAiSetupWorkflow workflow;
    private final LocalAiSetupWizardModel model;
    private final List<Node> pages = new ArrayList<>();
    private final StackPane pageHost = new StackPane();
    private final Label stepLabel = new Label();
    private final Label licenseDetails = new Label();
    private final Label licenseStatus = new Label();
    private final CheckBox licenseAccepted = new CheckBox(I18n.get("ai.local.wizard.license.accept"));
    private final ProgressBar installProgress = new ProgressBar(0);
    private final Label installStatus = new Label();
    private final Button abortInstallation = new Button(I18n.get("ai.local.wizard.abort"));
    private final Button retryInstallation = new Button(I18n.get("ai.local.wizard.retry"));
    private final Label finishSummary = new Label();
    private final ButtonType backType = new ButtonType(
        I18n.get("ai.local.wizard.back"), ButtonBar.ButtonData.BACK_PREVIOUS);
    private final ButtonType nextType = new ButtonType(
        I18n.get("ai.local.wizard.next"), ButtonBar.ButtonData.NEXT_FORWARD);
    private final ButtonType finishType = new ButtonType(
        I18n.get("ai.local.wizard.finish"), ButtonBar.ButtonData.FINISH);

    private List<LocalAiSetupWorkflow.ModelDetails> inspectedModels = List.of();
    private Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> inspectedSelections = Map.of();
    private LocalAiSetupWorkflow.Installation activeInstallation;
    private long inspectionGeneration;

    LocalAiSetupWizardDialog(
        Window owner,
        long systemMemoryBytes,
        LocalAiSetupWorkflow workflow
    ) {
        this(owner, systemMemoryBytes, workflow, null);
    }

    LocalAiSetupWizardDialog(
        Window owner,
        long systemMemoryBytes,
        LocalAiSetupWorkflow workflow,
        HuggingFaceModelCatalog.Role preferredRole
    ) {
        this.systemMemoryBytes = Math.max(0, systemMemoryBytes);
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.model = new LocalAiSetupWizardModel(this.systemMemoryBytes, preferredRole);
        if (owner != null) {
            initOwner(owner);
        }
        DialogThemeHelper.applyTheme(this);
        setTitle(I18n.get("ai.local.wizard.title"));
        setHeaderText(I18n.get("ai.local.wizard.header"));
        getDialogPane().getButtonTypes().addAll(backType, nextType, finishType, ButtonType.CANCEL);
        buildPages();

        VBox content = new VBox(12, stepLabel, pageHost);
        content.setPadding(new Insets(8));
        content.setPrefSize(700, 430);
        getDialogPane().setContent(content);
        updatePage();

        button(backType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (model.goBack()) {
                updatePage();
            }
        });
        button(nextType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            advance();
        });
        button(finishType).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!model.canFinish()) {
                event.consume();
            }
        });
        button(ButtonType.CANCEL).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (model.installationState() == LocalAiSetupWizardModel.InstallationState.RUNNING) {
                event.consume();
                cancelInstallation();
            }
        });
        setOnCloseRequest(event -> {
            if (model.installationState() == LocalAiSetupWizardModel.InstallationState.RUNNING) {
                event.consume();
                cancelInstallation();
            }
        });
    }

    private void buildPages() {
        pages.add(page(
            I18n.get("ai.local.wizard.privacy.title"),
            I18n.get("ai.local.wizard.privacy.body")));

        pages.add(page(
            I18n.get("ai.local.wizard.hardware.title"),
            I18n.get("ai.local.wizard.hardware.body", formatGib(systemMemoryBytes), backendSuggestion())));

        pages.add(buildRolesPage());
        pages.add(buildLicensePage());
        pages.add(buildInstallPage());

        VBox finishPage = page(
            I18n.get("ai.local.wizard.finish.title"),
            I18n.get("ai.local.wizard.finish.body"));
        finishSummary.setWrapText(true);
        finishPage.getChildren().add(finishSummary);
        pages.add(finishPage);
    }

    private VBox buildRolesPage() {
        VBox rolePage = page(
            I18n.get("ai.local.wizard.roles.title"),
            I18n.get("ai.local.wizard.roles.body"));
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        int row = 0;
        for (HuggingFaceModelCatalog.Role role : HuggingFaceModelCatalog.Role.values()) {
            List<HuggingFaceModelCatalog.Recommendation> candidates = model.recommendationsFor(role);
            ComboBox<HuggingFaceModelCatalog.Recommendation> combo = recommendationCombo(candidates);
            HuggingFaceModelCatalog.Recommendation selected = model.selectedRecommendation(role);
            combo.setValue(selected != null ? selected : (candidates.isEmpty() ? null : candidates.getFirst()));
            CheckBox enabled = new CheckBox(roleName(role));
            enabled.setSelected(selected != null);
            enabled.setDisable(candidates.isEmpty());
            combo.setDisable(!enabled.isSelected() || candidates.isEmpty());
            enabled.selectedProperty().addListener((ignored, previous, active) -> {
                combo.setDisable(!active);
                if (active) {
                    HuggingFaceModelCatalog.Recommendation value = combo.getValue();
                    if (value == null && !combo.getItems().isEmpty()) {
                        value = combo.getItems().getFirst();
                        combo.setValue(value);
                    }
                    model.selectRecommendation(role, value);
                } else {
                    model.selectRecommendation(role, null);
                }
                invalidateInspection();
                updatePage();
            });
            combo.valueProperty().addListener((ignored, previous, value) -> {
                if (enabled.isSelected()) {
                    model.selectRecommendation(role, value);
                    invalidateInspection();
                    updatePage();
                }
            });
            grid.add(enabled, 0, row);
            grid.add(combo, 1, row++);
        }
        rolePage.getChildren().add(grid);
        return rolePage;
    }

    private VBox buildLicensePage() {
        VBox licensePage = page(
            I18n.get("ai.local.wizard.license.title"),
            I18n.get("ai.local.wizard.license.body"));
        licenseStatus.setWrapText(true);
        licenseDetails.setWrapText(true);
        licenseAccepted.setDisable(true);
        licenseAccepted.selectedProperty().addListener((ignored, previous, selected) -> updatePage());
        licensePage.getChildren().addAll(licenseStatus, licenseDetails, licenseAccepted);
        return licensePage;
    }

    private VBox buildInstallPage() {
        VBox installPage = page(
            I18n.get("ai.local.wizard.install.title"),
            I18n.get("ai.local.wizard.install.body"));
        installProgress.setMaxWidth(Double.MAX_VALUE);
        installStatus.setWrapText(true);
        abortInstallation.setOnAction(event -> cancelInstallation());
        retryInstallation.setOnAction(event -> retryInstallation());
        retryInstallation.setVisible(false);
        retryInstallation.setManaged(false);
        installPage.getChildren().addAll(
            installProgress,
            installStatus,
            new HBox(8, abortInstallation, retryInstallation));
        return installPage;
    }

    private ComboBox<HuggingFaceModelCatalog.Recommendation> recommendationCombo(
        List<HuggingFaceModelCatalog.Recommendation> values
    ) {
        ComboBox<HuggingFaceModelCatalog.Recommendation> combo =
            new ComboBox<>(FXCollections.observableArrayList(values));
        combo.setMaxWidth(Double.MAX_VALUE);
        StringConverter<HuggingFaceModelCatalog.Recommendation> converter = new StringConverter<>() {
            @Override
            public String toString(HuggingFaceModelCatalog.Recommendation value) {
                if (value == null) {
                    return "";
                }
                return value.modelId() + " (" + value.quantization() + ")";
            }

            @Override
            public HuggingFaceModelCatalog.Recommendation fromString(String text) {
                return null;
            }
        };
        combo.setConverter(converter);
        combo.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(HuggingFaceModelCatalog.Recommendation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? "" : converter.toString(item));
            }
        });
        return combo;
    }

    private VBox page(String title, String body) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        Label text = new Label(body);
        text.setWrapText(true);
        VBox box = new VBox(14, heading, text);
        box.setPadding(new Insets(12));
        return box;
    }

    private void advance() {
        if (model.page() == LocalAiSetupWizardModel.Page.LICENSE) {
            if (!metadataReady() || !licenseAccepted.isSelected()) {
                return;
            }
            model.beginInstallation();
            updatePage();
            startInstallation();
            return;
        }
        if (model.goNext()) {
            updatePage();
            if (model.page() == LocalAiSetupWizardModel.Page.LICENSE) {
                inspectSelections();
            }
        }
    }

    private void inspectSelections() {
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> snapshot = model.selections();
        long generation = ++inspectionGeneration;
        inspectedModels = List.of();
        inspectedSelections = Map.of();
        licenseAccepted.setSelected(false);
        licenseAccepted.setDisable(true);
        licenseDetails.setText("");
        licenseStatus.setText(I18n.get("ai.local.wizard.license.loading"));
        updatePage();
        workflow.inspect(snapshot).whenComplete((details, error) -> Platform.runLater(() -> {
            if (generation != inspectionGeneration || !snapshot.equals(model.selections())) {
                return;
            }
            if (error != null) {
                inspectedModels = List.of();
                inspectedSelections = Map.of();
                licenseStatus.setText(I18n.get(
                    "ai.local.wizard.license.failed", errorMessage(rootCause(error))));
                licenseAccepted.setDisable(true);
            } else {
                inspectedModels = details != null ? List.copyOf(details) : List.of();
                inspectedSelections = snapshot;
                licenseStatus.setText(I18n.get("ai.local.wizard.license.ready", inspectedModels.size()));
                licenseDetails.setText(renderLicenseDetails(inspectedModels));
                licenseAccepted.setDisable(inspectedModels.isEmpty());
            }
            updatePage();
        }));
    }

    private String renderLicenseDetails(List<LocalAiSetupWorkflow.ModelDetails> details) {
        List<String> lines = new ArrayList<>();
        for (LocalAiSetupWorkflow.ModelDetails detail : details) {
            String roles = model.rolesFor(detail.recommendation()).stream()
                .map(this::roleName)
                .collect(java.util.stream.Collectors.joining(", "));
            String license = detail.model().license() != null
                ? detail.model().license() : I18n.get("ai.local.wizard.license.unknown");
            lines.add(I18n.get(
                "ai.local.wizard.license.model",
                roles,
                detail.recommendation().modelId(),
                detail.recommendation().quantization(),
                license,
                formatBytes(detail.downloadBytes())));
        }
        return String.join("\n\n", lines);
    }

    private void startInstallation() {
        installProgress.setProgress(0);
        installStatus.setText(I18n.get("ai.local.wizard.install.starting"));
        abortInstallation.setDisable(false);
        retryInstallation.setVisible(false);
        retryInstallation.setManaged(false);
        Map<HuggingFaceModelCatalog.Role, HuggingFaceModelCatalog.Recommendation> selections = model.selections();
        try {
            activeInstallation = workflow.install(selections, inspectedModels, this::showProgress);
        } catch (RuntimeException error) {
            model.failInstallation();
            installStatus.setText(I18n.get(
                "ai.local.wizard.install.failed", errorMessage(rootCause(error))));
            retryInstallation.setVisible(true);
            retryInstallation.setManaged(true);
            abortInstallation.setDisable(true);
            updatePage();
            return;
        }
        activeInstallation.completion().whenComplete((installed, error) -> Platform.runLater(() -> {
            activeInstallation = null;
            abortInstallation.setDisable(true);
            if (error != null) {
                Throwable cause = rootCause(error);
                if (cause instanceof CancellationException) {
                    model.cancelInstallation();
                    installStatus.setText(I18n.get("ai.local.wizard.install.cancelled"));
                } else {
                    model.failInstallation();
                    installStatus.setText(I18n.get(
                        "ai.local.wizard.install.failed", errorMessage(cause)));
                }
                retryInstallation.setVisible(true);
                retryInstallation.setManaged(true);
            } else {
                model.completeInstallation();
                int count = installed != null ? installed.size() : 0;
                finishSummary.setText(I18n.get("ai.local.wizard.finish.summary", count));
            }
            updatePage();
        }));
    }

    private void retryInstallation() {
        model.retryInstallation();
        updatePage();
        startInstallation();
    }

    private void cancelInstallation() {
        LocalAiSetupWorkflow.Installation installation = activeInstallation;
        if (installation != null) {
            abortInstallation.setDisable(true);
            installStatus.setText(I18n.get("ai.local.wizard.install.cancelling"));
            installation.cancel();
        }
    }

    private void showProgress(LocalAiSetupWorkflow.Progress progress) {
        Platform.runLater(() -> {
            if (model.installationState() != LocalAiSetupWizardModel.InstallationState.RUNNING) {
                return;
            }
            installProgress.setProgress(progress.fraction());
            String modelId = progress.modelId() != null ? progress.modelId() : "";
            String detail = progress.detail() != null ? progress.detail() : "";
            installStatus.setText(I18n.get(
                "ai.local.wizard.install.progress",
                I18n.get("ai.local.wizard.install.phase." + progress.phase().name().toLowerCase(Locale.ROOT)),
                modelId,
                progress.completedModels(),
                progress.totalModels(),
                detail));
        });
    }

    private void invalidateInspection() {
        inspectionGeneration++;
        inspectedModels = List.of();
        inspectedSelections = Map.of();
        licenseAccepted.setSelected(false);
        licenseAccepted.setDisable(true);
        licenseDetails.setText("");
        licenseStatus.setText("");
    }

    private boolean metadataReady() {
        return !inspectedModels.isEmpty() && inspectedSelections.equals(model.selections());
    }

    private void updatePage() {
        pageHost.getChildren().setAll(pages.get(model.pageIndex()));
        stepLabel.setText(I18n.get("ai.local.wizard.step", model.stepNumber(), model.pageCount()));
        button(backType).setDisable(!model.canGoBack());
        boolean showNext = model.page() != LocalAiSetupWizardModel.Page.INSTALL
            && model.page() != LocalAiSetupWizardModel.Page.FINISH;
        button(nextType).setVisible(showNext);
        button(nextType).setManaged(showNext);
        boolean nextDisabled = !model.canGoNext();
        if (model.page() == LocalAiSetupWizardModel.Page.LICENSE) {
            nextDisabled = !metadataReady() || !licenseAccepted.isSelected();
        }
        button(nextType).setDisable(nextDisabled);
        button(finishType).setVisible(model.page() == LocalAiSetupWizardModel.Page.FINISH);
        button(finishType).setManaged(model.page() == LocalAiSetupWizardModel.Page.FINISH);
        button(finishType).setDisable(!model.canFinish());
    }

    private Button button(ButtonType type) {
        return (Button) getDialogPane().lookupButton(type);
    }

    private String backendSuggestion() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64"))) {
            return "Metal";
        }
        return I18n.get("ai.local.wizard.backend.auto");
    }

    private String roleName(HuggingFaceModelCatalog.Role role) {
        return I18n.get("ai.local.wizard.role." + role.name().toLowerCase(Locale.ROOT));
    }

    private static String formatGib(long bytes) {
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024d * 1024d * 1024d));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit + 1 < units.length);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String errorMessage(Throwable error) {
        String value = error != null ? error.getMessage() : null;
        return value != null && !value.isBlank()
            ? value : (error != null ? error.getClass().getSimpleName() : "Unknown error");
    }
}
