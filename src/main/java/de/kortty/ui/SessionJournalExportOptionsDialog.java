package de.kortty.ui;

import de.kortty.core.SessionJournalAiSupport;
import de.kortty.core.SessionJournalExportFilter;
import de.kortty.core.SessionJournalExportProtection;
import de.kortty.core.SessionJournalExportService;
import de.kortty.core.SessionJournalService;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalMarkerDefinition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The export options both journal dialogs use: screenshots, archive password, any number of time
 * windows with a tolerance, a topic (text, regular expression, or delegated to the AI) and a
 * marker selection — with a live count of how many entries would be exported.
 *
 * <p>Before this existed the manager had its own inline options and the viewer had none at all,
 * so a single-journal export could not be filtered or password protected.</p>
 */
public final class SessionJournalExportOptionsDialog {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalExportOptionsDialog.class);

    /** Above this many journals a live preview costs more than it is worth. */
    private static final int MAX_PREVIEW_JOURNALS = 10;

    private static final int TOPIC_DEBOUNCE_MILLIS = 150;

    /** Viewport size of the scrolling content; the dialog adds its header and button bar around it. */
    private static final double CONTENT_WIDTH = 560;
    private static final double MAX_CONTENT_HEIGHT = 560;

    /** What to ask about. {@code pickRange} is offered only when the caller can drive a timeline. */
    public record Request(SessionJournalExportService.Format format, List<Path> journalDirs,
                          boolean archive, Window owner, List<SessionJournalExportFilter.TimeWindow> presetWindows,
                          Runnable pickRange) {

        public Request(SessionJournalExportService.Format format, List<Path> journalDirs,
                       boolean archive, Window owner) {
            this(format, journalDirs, archive, owner, List.of(), null);
        }
    }

    /** What the user chose; {@link SessionJournalExportProtection.Protection#NONE} means unencrypted. */
    public record ExportChoice(boolean includeScreenshots, SessionJournalExportProtection.Protection protection,
                               SessionJournalExportFilter filter) {
    }

    private SessionJournalExportOptionsDialog() {
    }

    public static Optional<ExportChoice> ask(SessionJournalService service, Request request) {
        return new Builder(service, request).show();
    }

    /** The built, unshown dialog — used by the manual's screenshot generator. */
    static Dialog<ButtonType> buildForCapture(SessionJournalService service, Request request) {
        return new Builder(service, request).buildDialog();
    }

    /** Holds the controls; an inner class keeps the wiring readable without a field-heavy dialog. */
    private static final class Builder {

        private final SessionJournalService service;
        private final Request request;
        private final boolean offersScreenshots;

        private final CheckBox includeCheck = new CheckBox(I18n.get("journal.export.includeScreenshots"));
        private final VBox windowRows = new VBox(6);
        private final Spinner<Integer> tolerance = new Spinner<>(0,
            SessionJournalExportFilter.MAX_TOLERANCE_MINUTES, SessionJournalExportFilter.DEFAULT_TOLERANCE_MINUTES);
        private final Label windowHint = new Label();
        private final TextField topicField = new TextField();
        private final CheckBox topicRegex = new CheckBox(I18n.get("journal.export.filter.topic.regex"));
        private final CheckBox topicAi = new CheckBox(I18n.get("journal.export.filter.topic.ai"));
        private final ToggleGroup markerGroup = new ToggleGroup();
        private final RadioButton markersAll = new RadioButton(I18n.get("journal.export.filter.markers.all"));
        private final RadioButton markersMarked = new RadioButton(I18n.get("journal.export.filter.markers.onlyMarked"));
        private final RadioButton markersSelected = new RadioButton(I18n.get("journal.export.filter.markers.specific"));
        private final FlowPane markerBoxes = new FlowPane(8, 6);
        private final Map<String, CheckBox> markerChecks = new LinkedHashMap<>();
        /** How the export is protected; the matching fields appear below only when chosen. */
        private enum ProtectionMode { NONE, PASSWORD, GPG }

        private final javafx.scene.control.ComboBox<ProtectionMode> protectionMode =
            new javafx.scene.control.ComboBox<>();
        private final VBox passwordBox = new VBox(6);
        private final VBox gpgBox = new VBox(6);
        private final javafx.scene.control.ComboBox<de.kortty.model.GPGKey> gpgKeyCombo =
            new javafx.scene.control.ComboBox<>();
        private final Label protectionHint = new Label();
        private final PasswordField passwordField = new PasswordField();
        private final PasswordField repeatField = new PasswordField();
        private final Label mismatch = new Label(I18n.get("journal.export.archive.mismatch"));
        private final Label previewLabel = new Label();
        private final Label bundleHint = new Label();

        private final AtomicInteger loadGeneration = new AtomicInteger();
        private final PauseTransition topicDebounce = new PauseTransition(Duration.millis(TOPIC_DEBOUNCE_MILLIS));
        private List<SessionJournalDocument> loaded = List.of();
        private boolean previewAvailable;
        private Node okButton;

        Builder(SessionJournalService service, Request request) {
            this.service = service;
            this.request = request;
            // The bundle is the only format without a screenshot toggle; it always carries the
            // images its entries reference. Everything else, filters included, applies to all three.
            this.offersScreenshots = request.format() != SessionJournalExportService.Format.HTML_BUNDLE;
        }

        /** Builds the fully wired dialog without showing it; also the seam the screenshot uses. */
        Dialog<ButtonType> buildDialog() {
            Dialog<ButtonType> dialog = new Dialog<>();
            DialogThemeHelper.applyTheme(dialog);
            if (request.owner() != null) {
                dialog.initOwner(request.owner());
            }
            dialog.setTitle(I18n.get("journal.export.title"));
            dialog.setHeaderText(headerText());
            dialog.setResizable(true);
            DialogGeometrySupport.installAutomatic(dialog, "sessionJournal.exportOptions");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            // The content grows with every added time window, so it scrolls inside a bounded
            // viewport instead of pushing the button bar off the bottom of the dialog.
            ScrollPane scroll = new ScrollPane(buildContent());
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scroll.setPrefViewportWidth(CONTENT_WIDTH);
            scroll.setPrefViewportHeight(MAX_CONTENT_HEIGHT);
            // -fx-background paints the viewport; without it the ScrollPane shows a light box.
            scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
            dialog.getDialogPane().setContent(scroll);
            dialog.getDialogPane().setPrefWidth(CONTENT_WIDTH + 40);
            okButton = dialog.getDialogPane().lookupButton(ButtonType.OK);

            wireListeners();
            loadDocumentsInBackground();
            revalidate();
            return dialog;
        }

        Optional<ExportChoice> show() {
            Dialog<ButtonType> dialog = buildDialog();
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return Optional.empty();
            }
            return Optional.of(new ExportChoice(
                !offersScreenshots || includeCheck.isSelected(), currentProtection(), currentFilter()));
        }

        private SessionJournalExportProtection.Protection currentProtection() {
            if (passwordChosen() && passwordField.getText() != null && !passwordField.getText().isEmpty()) {
                return SessionJournalExportProtection.Protection.password(passwordField.getText().toCharArray());
            }
            de.kortty.model.GPGKey key = gpgKeyCombo.getValue();
            if (gpgChosen() && key != null) {
                return SessionJournalExportProtection.Protection.gpg(key.getKeyId(), key.getPublicKeyPath());
            }
            return SessionJournalExportProtection.Protection.NONE;
        }

        private String headerText() {
            String label = switch (request.format()) {
                case PDF -> I18n.get("journal.export.pdf");
                case MARKDOWN -> I18n.get("journal.export.markdown");
                case HTML_BUNDLE -> I18n.get("journal.export.htmlBundle");
            };
            int count = request.journalDirs().size();
            return count > 1 ? label + " — " + I18n.get("journal.export.archive.hint", count) : label;
        }

        // ==== layout ====

        private VBox buildContent() {
            VBox content = new VBox(10);
            content.setPadding(new Insets(10));

            // Screenshots and encryption share the first row, so protection costs no height until
            // a password or GPG key is actually chosen.
            VBox protection = buildPasswordSection();
            javafx.scene.layout.HBox topRow = new javafx.scene.layout.HBox(10);
            topRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (offersScreenshots) {
                includeCheck.setSelected(true);
                topRow.getChildren().add(includeCheck);
            }
            javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
            javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            topRow.getChildren().addAll(spacer, new Label(I18n.get("journal.export.protection") + ":"), protectionMode);
            content.getChildren().addAll(topRow, protection);
            content.getChildren().addAll(
                titled("journal.export.filter.timeRange", buildTimeSection()),
                titled("journal.export.filter.topic", buildTopicSection()),
                titled("journal.export.filter.markers", buildMarkerSection()));

            previewLabel.setStyle("-fx-font-size: 0.8462em;");
            bundleHint.setWrapText(true);
            bundleHint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
            if (request.format() == SessionJournalExportService.Format.HTML_BUNDLE) {
                bundleHint.setText(I18n.get("journal.export.filter.bundleHint"));
            }
            content.getChildren().addAll(previewLabel, bundleHint);
            return content;
        }

        private static TitledPane titled(String key, Node body) {
            TitledPane pane = new TitledPane(I18n.get(key), body);
            pane.setCollapsible(false);
            return pane;
        }

        private VBox buildTimeSection() {
            for (SessionJournalExportFilter.TimeWindow preset : request.presetWindows()) {
                windowRows.getChildren().add(new WindowRow(preset, this::onWindowsChanged).node());
            }
            Button add = new Button(I18n.get("journal.export.filter.addWindow"));
            add.setOnAction(event -> {
                windowRows.getChildren().add(new WindowRow(null, this::onWindowsChanged).node());
                onWindowsChanged();
            });
            Button clear = new Button(I18n.get("journal.export.filter.clearWindows"));
            clear.setOnAction(event -> {
                windowRows.getChildren().clear();
                onWindowsChanged();
            });

            HBox buttons = new HBox(8, add, clear);
            if (request.pickRange() != null) {
                Button pick = new Button(I18n.get("journal.export.filter.pickFromJournal"));
                pick.setOnAction(event -> {
                    Node ok = okButton;
                    if (ok != null) {
                        ok.getScene().getWindow().hide();
                    }
                    request.pickRange().run();
                });
                buttons.getChildren().add(pick);
            }

            tolerance.setEditable(true);
            tolerance.setPrefWidth(90);
            tolerance.valueProperty().addListener((obs, old, value) -> revalidate());
            HBox toleranceRow = new HBox(8,
                new Label(I18n.get("journal.export.filter.tolerance")), tolerance,
                new Label(I18n.get("journal.export.filter.tolerance.unit")));
            toleranceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            windowHint.setWrapText(true);
            windowHint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
            windowHint.setManaged(false);
            windowHint.setVisible(false);

            VBox box = new VBox(8, windowRows, buttons, toleranceRow, windowHint);
            box.setPadding(new Insets(4));
            return box;
        }

        private VBox buildTopicSection() {
            topicField.setPromptText(I18n.get("journal.export.filter.topic.prompt"));
            boolean aiAvailable = aiAvailable();
            topicAi.setDisable(!aiAvailable);
            if (!aiAvailable) {
                topicAi.setTooltip(new Tooltip(I18n.get("journal.export.filter.ai.unavailable")));
            }
            // The AI reads prose, not a pattern; offering both at once only invites confusion.
            topicRegex.disableProperty().bind(topicAi.selectedProperty());
            VBox box = new VBox(8, topicField, new HBox(12, topicRegex, topicAi));
            box.setPadding(new Insets(4));
            return box;
        }

        private static boolean aiAvailable() {
            try {
                return SessionJournalAiSupport.applicationInvoker().isAvailable();
            } catch (Exception e) {
                logger.debug("Could not determine AI availability for the export dialog: {}", e.getMessage());
                return false;
            }
        }

        private VBox buildMarkerSection() {
            markersAll.setToggleGroup(markerGroup);
            markersMarked.setToggleGroup(markerGroup);
            markersSelected.setToggleGroup(markerGroup);
            markersAll.setSelected(true);
            markerBoxes.disableProperty().bind(markersSelected.selectedProperty().not());
            markerBoxes.setPadding(new Insets(2, 0, 0, 20));
            VBox box = new VBox(6, markersAll, markersMarked, markersSelected, markerBoxes);
            box.setPadding(new Insets(4));
            return box;
        }

        /** None, an AES-256 password ZIP, or GPG for one of the keys korTTY manages. */
        private VBox buildPasswordSection() {
            List<de.kortty.model.GPGKey> keys = gpgKeys();
            protectionMode.getItems().setAll(ProtectionMode.values());
            protectionMode.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(ProtectionMode mode) {
                    if (mode == null) {
                        return "";
                    }
                    return switch (mode) {
                        case NONE -> I18n.get("journal.export.protection.none");
                        case PASSWORD -> I18n.get(request.archive()
                            ? "journal.export.archive.password" : "journal.export.protection.passwordZip");
                        case GPG -> I18n.get("journal.export.protection.gpg");
                    };
                }

                @Override
                public ProtectionMode fromString(String text) {
                    return null;
                }
            });
            protectionMode.setValue(ProtectionMode.NONE);

            mismatch.setStyle("-fx-text-fill: #cf222e; -fx-font-size: 0.8462em;");
            mismatch.setVisible(false);
            mismatch.managedProperty().bind(mismatch.visibleProperty());
            passwordField.setPromptText(I18n.get("journal.export.archive.passwordField"));
            repeatField.setPromptText(I18n.get("journal.export.archive.passwordRepeat"));
            javafx.scene.layout.HBox fields = new javafx.scene.layout.HBox(8, passwordField, repeatField);
            javafx.scene.layout.HBox.setHgrow(passwordField, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.layout.HBox.setHgrow(repeatField, javafx.scene.layout.Priority.ALWAYS);
            passwordBox.getChildren().setAll(fields, mismatch);

            gpgKeyCombo.getItems().setAll(keys);
            gpgKeyCombo.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(de.kortty.model.GPGKey key) {
                    if (key == null) {
                        return "";
                    }
                    String name = key.getName() != null ? key.getName() : "";
                    String email = key.getEmail() != null && !key.getEmail().isBlank() ? " <" + key.getEmail() + ">" : "";
                    return name + email + " · " + key.getKeyId();
                }

                @Override
                public de.kortty.model.GPGKey fromString(String text) {
                    return null;
                }
            });
            gpgKeyCombo.setPromptText(I18n.get("journal.export.protection.gpgKey"));
            gpgKeyCombo.setMaxWidth(Double.MAX_VALUE);
            if (!keys.isEmpty()) {
                gpgKeyCombo.setValue(keys.get(0));
            }
            gpgBox.getChildren().setAll(gpgKeyCombo);

            protectionHint.setWrapText(true);
            protectionHint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");
            VBox box = new VBox(6, passwordBox, gpgBox, protectionHint);
            updateProtectionHint();
            return box;
        }

        private boolean passwordChosen() {
            return protectionMode.getValue() == ProtectionMode.PASSWORD;
        }

        private boolean gpgChosen() {
            return protectionMode.getValue() == ProtectionMode.GPG;
        }

        private static List<de.kortty.model.GPGKey> gpgKeys() {
            try {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getGpgKeyManager() != null) {
                    return app.getGpgKeyManager().getAllKeys().stream()
                        .filter(key -> key.getKeyId() != null && !key.getKeyId().isBlank())
                        .toList();
                }
            } catch (RuntimeException e) {
                logger.debug("GPG keys not available: {}", e.getMessage());
            }
            return List.of();
        }

        private void updateProtectionHint() {
            String hint = "";
            if (gpgChosen()) {
                hint = I18n.get(gpgKeyCombo.getItems().isEmpty()
                    ? "journal.export.protection.gpgNoKeys" : "journal.export.protection.gpgHint");
            } else if (passwordChosen() && !request.archive()) {
                hint = I18n.get("journal.export.protection.passwordZipHint");
            }
            protectionHint.setText(hint);
            show(protectionHint, !hint.isEmpty());
            show(passwordBox, passwordChosen());
            show(gpgBox, gpgChosen() && !gpgKeyCombo.getItems().isEmpty());
            show(passwordBox.getParent(), !hint.isEmpty() || passwordChosen() || gpgChosen());
        }

        /**
         * Shows or hides a node and relayouts: toggling {@code managed} inside a ScrollPane does
         * not trigger a relayout on its own.
         */
        private static void show(Node node, boolean visible) {
            if (node == null) {
                return;
            }
            node.setVisible(visible);
            node.setManaged(visible);
            if (node.getParent() != null) {
                node.getParent().requestLayout();
            }
        }

        // ==== behaviour ====

        private void wireListeners() {
            topicDebounce.setOnFinished(event -> revalidate());
            topicField.textProperty().addListener((obs, old, value) -> topicDebounce.playFromStart());
            topicRegex.selectedProperty().addListener((obs, old, value) -> revalidate());
            topicAi.selectedProperty().addListener((obs, old, value) -> revalidate());
            markerGroup.selectedToggleProperty().addListener((obs, old, value) -> revalidate());
            protectionMode.valueProperty().addListener((obs, old, value) -> {
                updateProtectionHint();
                revalidate();
            });
            gpgKeyCombo.valueProperty().addListener((obs, old, value) -> revalidate());
            passwordField.textProperty().addListener((obs, old, value) -> revalidate());
            repeatField.textProperty().addListener((obs, old, value) -> revalidate());
        }

        private void onWindowsChanged() {
            revalidate();
        }

        /**
         * Loads the documents off the FX thread once; filtering them afterwards is pure and takes
         * microseconds, so every keystroke can recompute synchronously.
         */
        private void loadDocumentsInBackground() {
            List<Path> dirs = request.journalDirs();
            if (dirs.size() > MAX_PREVIEW_JOURNALS) {
                previewAvailable = false;
                previewLabel.setText(I18n.get("journal.export.filter.preview.unavailable"));
                populateMarkerBoxes(List.of());
                return;
            }
            previewLabel.setText(I18n.get("journal.export.filter.preview.loading"));
            int generation = loadGeneration.incrementAndGet();
            Thread loader = new Thread(() -> {
                List<SessionJournalDocument> documents = new ArrayList<>(dirs.size());
                for (Path dir : dirs) {
                    try {
                        documents.add(service.loadDocument(dir));
                    } catch (Exception e) {
                        logger.warn("Could not load {} for the export preview: {}", dir.getFileName(), e.getMessage());
                    }
                }
                Platform.runLater(() -> {
                    if (generation != loadGeneration.get()) {
                        return;
                    }
                    loaded = documents;
                    previewAvailable = true;
                    populateMarkerBoxes(collectMarkers(documents));
                    revalidate();
                });
            }, "SessionJournal-ExportPreview");
            loader.setDaemon(true);
            loader.start();
        }

        private static List<SessionJournalMarkerDefinition> collectMarkers(List<SessionJournalDocument> documents) {
            Map<String, SessionJournalMarkerDefinition> used = new LinkedHashMap<>();
            for (SessionJournalDocument document : documents) {
                for (SessionJournalMarkerDefinition definition : SessionJournalExportFilter.usedMarkers(document)) {
                    used.putIfAbsent(definition.getId(), definition);
                }
            }
            return new ArrayList<>(used.values());
        }

        private void populateMarkerBoxes(List<SessionJournalMarkerDefinition> definitions) {
            markerChecks.clear();
            markerBoxes.getChildren().clear();
            if (definitions.isEmpty()) {
                markersSelected.setDisable(true);
                markersMarked.setDisable(true);
                return;
            }
            markersSelected.setDisable(false);
            markersMarked.setDisable(false);
            for (SessionJournalMarkerDefinition definition : definitions) {
                CheckBox box = new CheckBox(de.kortty.core.SessionJournalMarkers.displayName(definition));
                box.setGraphic(swatch(definition));
                box.selectedProperty().addListener((obs, old, value) -> revalidate());
                markerChecks.put(definition.getId(), box);
                markerBoxes.getChildren().add(box);
            }
        }

        private static Rectangle swatch(SessionJournalMarkerDefinition definition) {
            Rectangle rectangle = new Rectangle(10, 10);
            rectangle.setArcWidth(4);
            rectangle.setArcHeight(4);
            rectangle.setFill(colorOf(definition));
            return rectangle;
        }

        private static Color colorOf(SessionJournalMarkerDefinition definition) {
            java.awt.Color awt = de.kortty.core.SessionJournalMarkers.awtColor(
                definition, new java.awt.Color(0x6e, 0x77, 0x81));
            return Color.rgb(awt.getRed(), awt.getGreen(), awt.getBlue());
        }

        // ==== filter assembly, validation and preview ====

        private List<SessionJournalExportFilter.TimeWindow> currentWindows() {
            List<SessionJournalExportFilter.TimeWindow> windows = new ArrayList<>();
            for (Node node : windowRows.getChildren()) {
                WindowRow row = (WindowRow) node.getUserData();
                SessionJournalExportFilter.TimeWindow window = row.toWindow();
                if (window != null && !window.isEmpty()) {
                    windows.add(window);
                }
            }
            return windows;
        }

        private SessionJournalExportFilter currentFilter() {
            Set<String> selectedMarkers = markerChecks.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            SessionJournalExportFilter.MarkerMode mode;
            if (markersMarked.isSelected()) {
                mode = SessionJournalExportFilter.MarkerMode.MARKED;
            } else if (markersSelected.isSelected() && !selectedMarkers.isEmpty()) {
                mode = SessionJournalExportFilter.MarkerMode.SELECTED;
            } else {
                mode = SessionJournalExportFilter.MarkerMode.ALL;
            }
            return new SessionJournalExportFilter(currentWindows(), tolerance.getValue(),
                topicField.getText(), topicRegex.isSelected(), topicAi.isSelected(),
                mode, selectedMarkers, ZoneId.systemDefault());
        }

        private void revalidate() {
            List<String> problems = new ArrayList<>();
            boolean rowError = false;
            for (Node node : windowRows.getChildren()) {
                WindowRow row = (WindowRow) node.getUserData();
                rowError |= row.markProblems();
            }
            if (rowError) {
                problems.add(I18n.get("journal.export.filter.time.invalid"));
            }
            SessionJournalExportFilter filter = currentFilter();
            if (invalidTopicRegex(filter)) {
                problems.add(I18n.get("journal.export.filter.topic.regex.invalid"));
            }
            updateWindowHint(filter);

            boolean passwordProblem = (passwordChosen() && !passwordsMatch())
                || (gpgChosen() && gpgKeyCombo.getValue() == null);
            mismatch.setVisible(passwordProblem && !repeatField.getText().isEmpty());

            boolean emptySelection = updatePreview(filter);
            if (okButton != null) {
                okButton.setDisable(!problems.isEmpty() || passwordProblem || emptySelection);
            }
        }

        private boolean passwordsMatch() {
            String password = passwordField.getText();
            return password != null && !password.isEmpty() && password.equals(repeatField.getText());
        }

        private static boolean invalidTopicRegex(SessionJournalExportFilter filter) {
            return filter.hasTopicFilter() && filter.topicRegex() && filter.hasInvalidTopicRegex();
        }

        private void updateWindowHint(SessionJournalExportFilter filter) {
            String hint = null;
            for (SessionJournalExportFilter.TimeWindow window : filter.windows()) {
                if (window.wrapsMidnight()) {
                    hint = I18n.get("journal.export.filter.time.wrap");
                    break;
                }
            }
            windowHint.setText(hint != null ? hint : "");
            windowHint.setVisible(hint != null);
            windowHint.setManaged(hint != null);
        }

        /** Returns true when the export would produce nothing and OK must therefore stay disabled. */
        private boolean updatePreview(SessionJournalExportFilter filter) {
            if (!previewAvailable) {
                return false;
            }
            if (filter.hasTopicFilter() && filter.topicAi()) {
                // A text count would be misleading here: the model decides during the export.
                previewLabel.setStyle("-fx-font-size: 0.8462em;");
                previewLabel.setText(I18n.get("journal.export.filter.preview.ai"));
                return false;
            }
            int kept = 0;
            int total = 0;
            for (SessionJournalDocument document : loaded) {
                SessionJournalExportFilter.Result result = SessionJournalExportFilter.apply(document, filter);
                kept += result.keptEntries();
                total += result.totalEntries();
            }
            boolean empty = filter.isActive() && kept == 0;
            previewLabel.setStyle(empty
                ? "-fx-font-size: 0.8462em; -fx-text-fill: #cf222e;"
                : "-fx-font-size: 0.8462em;");
            previewLabel.setText(loaded.size() > 1
                ? I18n.get("journal.export.filter.preview.multi", kept, total, loaded.size())
                : I18n.get("journal.export.filter.preview", kept, total));
            return empty;
        }
    }

    /**
     * One editable time window. Dates are optional (a window without them repeats on every day of
     * the journal) and the times are parsed forgivingly, so "8", "0800" and "8:00" all work.
     */
    private static final class WindowRow {

        private final HBox node;
        private final DatePicker fromDate = new DatePicker();
        private final TextField fromTime = new TextField();
        private final DatePicker toDate = new DatePicker();
        private final TextField toTime = new TextField();

        WindowRow(SessionJournalExportFilter.TimeWindow preset, Runnable onChange) {
            configure(fromDate, fromTime, onChange);
            configure(toDate, toTime, onChange);
            if (preset != null) {
                fromDate.setValue(preset.fromDate());
                toDate.setValue(preset.toDate());
                fromTime.setText(SessionJournalExportFilter.formatTimeOfDay(preset.fromTime()));
                toTime.setText(SessionJournalExportFilter.formatTimeOfDay(preset.toTime()));
            }
            Button remove = new Button("✕");
            remove.setTooltip(new Tooltip(I18n.get("journal.export.filter.removeWindow")));

            node = new HBox(6, fromDate, fromTime, new Label("–"), toDate, toTime, remove);
            node.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            node.setUserData(this);
            HBox.setHgrow(fromDate, Priority.SOMETIMES);
            HBox.setHgrow(toDate, Priority.SOMETIMES);
            remove.setOnAction(event -> {
                ((VBox) node.getParent()).getChildren().remove(node);
                onChange.run();
            });
        }

        private static void configure(DatePicker date, TextField time, Runnable onChange) {
            date.setPrefWidth(140);
            date.setPromptText(I18n.get("journal.export.filter.anyDate"));
            date.valueProperty().addListener((obs, old, value) -> onChange.run());
            time.setPrefWidth(62);
            time.setPromptText("HH:MM");
            time.textProperty().addListener((obs, old, value) -> onChange.run());
        }

        HBox node() {
            return node;
        }

        SessionJournalExportFilter.TimeWindow toWindow() {
            return new SessionJournalExportFilter.TimeWindow(
                fromDate.getValue(), parse(fromTime), toDate.getValue(), parse(toTime));
        }

        private static LocalTime parse(TextField field) {
            return SessionJournalExportFilter.parseTimeOfDay(field.getText());
        }

        /** Highlights unusable input and reports whether this row is broken. */
        boolean markProblems() {
            boolean fromBroken = unparsable(fromTime);
            boolean toBroken = unparsable(toTime);
            LocalDate from = fromDate.getValue();
            LocalDate to = toDate.getValue();
            boolean datesSwapped = from != null && to != null && from.isAfter(to);
            style(fromTime, fromBroken);
            style(toTime, toBroken);
            style(fromDate, datesSwapped);
            style(toDate, datesSwapped);
            return fromBroken || toBroken || datesSwapped;
        }

        private static boolean unparsable(TextField field) {
            String text = field.getText();
            return text != null && !text.isBlank()
                && SessionJournalExportFilter.parseTimeOfDay(text) == null;
        }

        private static void style(Node node, boolean broken) {
            node.setStyle(broken ? "-fx-border-color: #cf222e; -fx-border-radius: 3;" : "");
        }
    }
}
