package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ExternalAiSkillCandidate;
import de.kortty.core.ExternalAiSkillClient;
import de.kortty.core.ExternalAiSkillDocument;
import de.kortty.core.ExternalAiSkillException;
import de.kortty.core.ExternalAiSkillSupport;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillProvider;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI Skills → External → Import from the internet…: searches one provider, previews a SKILL.md and
 * imports the selected skills — always deactivated ({@link ExternalAiSkillSupport#toSkill}).
 *
 * <p>The result is the list of new skills, empty when nothing was imported. Network calls run off the
 * JavaFX thread; credentials are decrypted before each call, on the JavaFX thread.
 */
final class ExternalAiSkillImportDialog extends ThemeAwareDialog<List<AiSkill>> {

    private static final String WARNING_STYLE = "-fx-text-fill: #d97706;";

    private final KorTTYApplication app;
    private final List<AiSkillProvider> providers;
    private final List<AiSkill> existingSkills;

    private final ComboBox<AiSkillProvider> providerCombo = new ComboBox<>();
    private final TextField queryField = new TextField();
    private final Button searchButton = new Button(I18n.get("settings.aiSkills.external.dialog.search"));
    private final Label hintLabel = new Label();
    private final TableView<ExternalAiSkillCandidate> resultTable = new TableView<>();
    private final TextArea previewArea = new TextArea();
    private final Label statusLabel = new Label();
    private final Button importButton = new Button(I18n.get("settings.aiSkills.external.dialog.importSelected"));
    /** Shown when GitHub refuses a keyword search without a token: repeats the search on SkillsMP. */
    private final Button searchSkillsMpButton = new Button(I18n.get("settings.aiSkills.external.dialog.searchSkillsMp"));

    /** Downloaded SKILL.md files by candidate reference, so a previewed skill is not fetched twice. */
    private final Map<String, ExternalAiSkillDocument> documents = new HashMap<>();
    /** Provider and credentials of the last search; previews and the import use the same. */
    private AiSkillProvider searchedProvider;
    private ExternalAiSkillClient client;
    private int previewGeneration;
    private List<AiSkill> imported = List.of();

    ExternalAiSkillImportDialog(Window owner, KorTTYApplication app, List<AiSkillProvider> providers, List<AiSkill> existingSkills) {
        this.app = app;
        this.providers = providers;
        this.existingSkills = existingSkills;
        setTitle(I18n.get("settings.aiSkills.external.dialog.title"));
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        providerCombo.getItems().setAll(providers.stream().filter(AiSkillProvider::isEnabled).toList());
        providerCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(AiSkillProvider provider) {
                return provider == null ? "" : provider.displayName() + " — " + ExternalAiSkillUiSupport.typeLabel(provider.getType());
            }

            @Override
            public AiSkillProvider fromString(String string) {
                return null;
            }
        });
        providerCombo.valueProperty().addListener((obs, oldValue, newValue) -> onProviderChanged(newValue));

        queryField.setPromptText(I18n.get("settings.aiSkills.external.dialog.query.prompt"));
        queryField.setOnAction(event -> search());
        HBox.setHgrow(queryField, Priority.ALWAYS);
        searchButton.setDefaultButton(true);
        searchButton.setOnAction(event -> search());
        HBox searchRow = new HBox(8, new Label(I18n.get("settings.aiSkills.external.dialog.provider")), providerCombo,
            queryField, searchButton);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        hintLabel.setWrapText(true);
        hintLabel.setStyle("-fx-font-size: 0.8462em;");

        buildResultTable();
        previewArea.setEditable(false);
        previewArea.setWrapText(true);
        previewArea.setStyle("-fx-font-family: monospace;");
        VBox previewBox = new VBox(6, new Label(I18n.get("settings.aiSkills.external.dialog.preview")), previewArea);
        VBox.setVgrow(previewArea, Priority.ALWAYS);
        SplitPane split = new SplitPane(resultTable, previewBox);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);

        Label warningLabel = new Label(I18n.get("settings.aiSkills.external.dialog.warning"));
        warningLabel.setWrapText(true);
        warningLabel.setStyle(WARNING_STYLE);
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        importButton.setDisable(true);
        importButton.setOnAction(event -> importSelected());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        searchSkillsMpButton.setVisible(false);
        searchSkillsMpButton.managedProperty().bind(searchSkillsMpButton.visibleProperty());
        searchSkillsMpButton.setOnAction(event -> searchOnSkillsMp());
        HBox actionRow = new HBox(8, statusLabel, searchSkillsMpButton, spacer, importButton);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, searchRow, hintLabel, split, warningLabel, actionRow);
        root.setPadding(new Insets(14));
        root.setPrefSize(1000, 640);
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        setResultConverter(buttonType -> imported);

        if (providerCombo.getItems().isEmpty()) {
            statusLabel.setText(I18n.get("settings.aiSkills.external.dialog.noProvider"));
            searchButton.setDisable(true);
        } else {
            providerCombo.getSelectionModel().selectFirst();
        }
        Platform.runLater(queryField::requestFocus);
    }

    private void buildResultTable() {
        TableColumn<ExternalAiSkillCandidate, String> nameColumn =
            new TableColumn<>(I18n.get("settings.aiSkills.external.dialog.column.name"));
        nameColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            (isImported(cell.getValue()) ? "✓ " : "") + cell.getValue().name()));
        nameColumn.setPrefWidth(180);
        nameColumn.setMinWidth(140);
        TableColumn<ExternalAiSkillCandidate, String> sourceColumn =
            new TableColumn<>(I18n.get("settings.aiSkills.external.dialog.column.source"));
        sourceColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().author()));
        sourceColumn.setPrefWidth(140);
        TableColumn<ExternalAiSkillCandidate, String> starsColumn =
            new TableColumn<>(I18n.get("settings.aiSkills.external.dialog.column.stars"));
        starsColumn.setCellValueFactory(cell -> new SimpleStringProperty(
            cell.getValue().stars() != null ? String.valueOf(cell.getValue().stars()) : ""));
        starsColumn.setPrefWidth(80);
        starsColumn.setMinWidth(80);
        TableColumn<ExternalAiSkillCandidate, String> descriptionColumn =
            new TableColumn<>(I18n.get("settings.aiSkills.external.dialog.column.description"));
        descriptionColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().description()));
        descriptionColumn.setPrefWidth(320);
        nameColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                ExternalAiSkillCandidate row = empty || getTableRow() == null ? null : getTableRow().getItem();
                setTooltip(row != null && isImported(row)
                    ? new javafx.scene.control.Tooltip(I18n.get("settings.aiSkills.external.dialog.alreadyImported"))
                    : null);
            }
        });
        resultTable.getColumns().setAll(List.of(nameColumn, sourceColumn, starsColumn, descriptionColumn));
        resultTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        resultTable.setPlaceholder(new Label(""));
        resultTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> preview(newValue));
        resultTable.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<ExternalAiSkillCandidate>) change -> updateImportButton());
    }

    private void onProviderChanged(AiSkillProvider provider) {
        resultTable.getItems().clear();
        previewArea.clear();
        documents.clear();
        client = null;
        searchedProvider = null;
        updateImportButton();
        if (provider == null) {
            hintLabel.setText("");
            return;
        }
        hintLabel.setText(I18n.get("settings.aiSkills.external.hint." + provider.getType().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private boolean isImported(ExternalAiSkillCandidate candidate) {
        return ExternalAiSkillSupport.findImported(existingSkills, candidate.reference()) != null;
    }

    /** The first enabled SkillsMP profile, or {@code null}. */
    private AiSkillProvider skillsMpProvider() {
        return providerCombo.getItems().stream()
            .filter(provider -> provider.getType() == de.kortty.model.AiSkillProviderType.SKILLSMP)
            .findFirst().orElse(null);
    }

    private void searchOnSkillsMp() {
        AiSkillProvider skillsMp = skillsMpProvider();
        if (skillsMp != null) {
            providerCombo.getSelectionModel().select(skillsMp);
            search();
        }
    }

    private void search() {
        searchSkillsMpButton.setVisible(false);
        AiSkillProvider provider = providerCombo.getValue();
        String query = queryField.getText() != null ? queryField.getText().trim() : "";
        if (provider == null || query.isEmpty()) {
            return;
        }
        Map<String, String> secrets = ExternalAiSkillUiSupport.unlockSecrets(dialogWindow(), app,
            ExternalAiSkillUiSupport.involvedProviders(provider, providers));
        if (secrets == null) {
            return;
        }
        ExternalAiSkillClient searchClient = ExternalAiSkillSupport.clientFor(provider,
            ExternalAiSkillUiSupport.secretLookup(secrets), providers);
        setBusy(true);
        statusLabel.setText(I18n.get("settings.aiSkills.external.dialog.searching"));
        resultTable.getItems().clear();
        previewArea.clear();
        documents.clear();
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return searchClient.search(query);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            })
            .whenComplete((candidates, failure) -> Platform.runLater(() -> {
                setBusy(false);
                if (providerCombo.getValue() != provider) {
                    return;
                }
                if (failure != null) {
                    statusLabel.setText(ExternalAiSkillUiSupport.describe(failure));
                    searchSkillsMpButton.setVisible(ExternalAiSkillUiSupport.reasonOf(failure)
                        == ExternalAiSkillException.Reason.TOKEN_REQUIRED && skillsMpProvider() != null);
                    return;
                }
                searchedProvider = provider;
                client = searchClient;
                resultTable.getItems().setAll(candidates);
                statusLabel.setText(candidates.isEmpty()
                    ? I18n.get("settings.aiSkills.external.dialog.noResults")
                    : I18n.get("settings.aiSkills.external.dialog.results", candidates.size()));
                if (!candidates.isEmpty()) {
                    resultTable.getSelectionModel().selectFirst();
                }
            }));
    }

    private void preview(ExternalAiSkillCandidate candidate) {
        int generation = ++previewGeneration;
        if (candidate == null || client == null) {
            previewArea.clear();
            return;
        }
        ExternalAiSkillDocument cached = documents.get(candidate.reference());
        if (cached != null) {
            previewArea.setText(cached.markdown());
            return;
        }
        previewArea.setText(I18n.get("settings.aiSkills.external.dialog.previewLoading"));
        ExternalAiSkillClient previewClient = client;
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return previewClient.fetch(candidate.reference());
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            })
            .whenComplete((document, failure) -> Platform.runLater(() -> {
                if (failure == null) {
                    documents.put(candidate.reference(), document);
                }
                if (generation != previewGeneration) {
                    return;
                }
                previewArea.setText(failure != null ? ExternalAiSkillUiSupport.describe(failure) : document.markdown());
            }));
    }

    private List<ExternalAiSkillCandidate> importableSelection() {
        List<ExternalAiSkillCandidate> selection = new ArrayList<>();
        for (ExternalAiSkillCandidate candidate : resultTable.getSelectionModel().getSelectedItems()) {
            if (candidate != null && !isImported(candidate)) {
                selection.add(candidate);
            }
        }
        return selection;
    }

    private void updateImportButton() {
        importButton.setDisable(client == null || importableSelection().isEmpty());
    }

    private void importSelected() {
        List<ExternalAiSkillCandidate> selection = importableSelection();
        if (selection.isEmpty() || client == null) {
            statusLabel.setText(I18n.get("settings.aiSkills.external.dialog.nothingSelected"));
            return;
        }
        AiSkillProvider provider = searchedProvider;
        ExternalAiSkillClient importClient = client;
        Map<String, ExternalAiSkillDocument> known = new HashMap<>(documents);
        setBusy(true);
        statusLabel.setText(I18n.get("settings.aiSkills.external.dialog.importing", selection.size()));
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    long now = System.currentTimeMillis();
                    List<AiSkill> skills = new ArrayList<>();
                    List<String> references = new ArrayList<>();
                    for (ExternalAiSkillCandidate candidate : selection) {
                        ExternalAiSkillDocument document = known.get(candidate.reference());
                        if (document == null) {
                            document = importClient.fetch(candidate.reference());
                        }
                        // Two hits can resolve to the same SKILL.md (e.g. owner/repo@x and its tree URL).
                        if (references.contains(document.reference())) {
                            continue;
                        }
                        references.add(document.reference());
                        skills.add(ExternalAiSkillSupport.toSkill(document, provider, now));
                    }
                    return skills;
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            })
            .whenComplete((skills, failure) -> Platform.runLater(() -> {
                setBusy(false);
                if (failure != null) {
                    statusLabel.setText(ExternalAiSkillUiSupport.describe(failure));
                    return;
                }
                List<AiSkill> fresh = new ArrayList<>();
                for (AiSkill skill : skills) {
                    if (ExternalAiSkillSupport.findImported(existingSkills, skill.getExternalSource().getReference()) == null) {
                        fresh.add(skill);
                    }
                }
                imported = fresh;
                close();
            }));
    }

    private void setBusy(boolean busy) {
        searchButton.setDisable(busy || providerCombo.getValue() == null);
        providerCombo.setDisable(busy);
        queryField.setDisable(busy);
        if (busy) {
            importButton.setDisable(true);
        } else {
            updateImportButton();
        }
    }

    private Window dialogWindow() {
        return getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
    }
}
