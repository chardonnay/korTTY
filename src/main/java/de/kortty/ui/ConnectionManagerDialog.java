package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.ConnectionGroupColors;
import de.kortty.core.ConnectionOverviewExport;
import de.kortty.core.CredentialManager;
import de.kortty.core.HostKeyCheckGroupExemptions;
import de.kortty.model.GlobalSettings;
import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import de.kortty.model.SSHKey;
import de.kortty.model.StoredCredential;
import de.kortty.teamwork.TeamworkSyncService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dialog for managing saved connections with tree view.
 */
public class ConnectionManagerDialog extends ThemeAwareDialog<ServerConnection> {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerDialog.class);
    
    /** Sentinel value for "use temporary SSH key for all teamwork" in the auth combo. */
    public static final Object TEAMWORK_AUTH_TEMPORARY_KEY = new Object();
    
    private final KorTTYApplication app;
    private final ConfigurationManager configManager;
    private final CredentialManager credentialManager;
    private final char[] masterPassword;
    private final ObservableList<ServerConnection> connections;
    private final ObservableList<ServerConnection> teamworkConnections;
    private final TextField searchField;
    private final Stage owner;
    private ConnectionManagerTreeView treeView;
    private ConnectionManagerTreeView teamworkTreeView;
    /** What the library on the left shows in the middle. */
    private Scope scope = Scope.ALL;
    /** Whether the middle shows the map instead of the list. */
    private boolean mapMode;
    private final ToggleGroup scopeToggles = new ToggleGroup();
    private final VBox sidebarItems = new VBox(1);
    private final Label scopeTitle = new Label();
    private final Label scopeCount = new Label();
    /** The theme's text colour, which the toolbar's glyph contours follow. */
    private final Label themeText = new Label();
    private final ConnectionMapView mapView = new ConnectionMapView();
    private final ConnectionInspectorPane inspector = new ConnectionInspectorPane();
    private HBox teamworkAuthBar;
    private ToggleButton listViewButton;
    private ToggleButton mapViewButton;
    private VBox teamworkToolGroup;
    /** Open tabs of every window by connection id. */
    private Map<String, Integer> openTabCounts = Map.of();
    private Button undoButton;
    private Button createFolderButton;
    private Button renameGroupButton;
    private Button refreshTeamworkButton;
    private Button restoreTeamworkButton;
    private Button addButton;
    private Button editButton;
    private Button deleteButton;
    private Button duplicateButton;
    private Button exportButton;
    private Button importButton;
    private Button overviewButton;
    private ButtonType connectButtonType;
    private Runnable teamworkCacheUpdateListener;
    /** When true, combo/username listeners must not call saveTeamworkAuthSelection (e.g. during applyTeamworkAuthFromSettings). */
    private boolean suppressTeamworkAuthSave;
    /** Optional callback run when connections are saved successfully (e.g. to refresh open terminal tabs). */
    private Runnable onConnectionsSavedCallback;
    private final TableView<ServerConnection> table; // Keep for compatibility, but hide it
    
    public void setOnConnectionsSavedCallback(Runnable onConnectionsSavedCallback) {
        this.onConnectionsSavedCallback = onConnectionsSavedCallback;
    }
    
    public ConnectionManagerDialog(Stage owner, KorTTYApplication app) {
        this.app = app;
        this.configManager = app.getConfigManager();
        this.credentialManager = app.getCredentialManager();
        this.masterPassword = app.getMasterPasswordManager().getMasterPassword();
        this.owner = owner;

        setTitle(I18n.get("connectionManager.title"));
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);

        // Initialize connections list (local only)
        connections = FXCollections.observableArrayList(configManager.getConnections());
        teamworkConnections = FXCollections.observableArrayList();

        // Create hidden table for compatibility
        table = new TableView<>();
        table.setVisible(false);
        table.setManaged(false);

        refreshOpenTabCounts();
        // The list in the middle: the local tree, or the teamwork tree while Teamwork is chosen on the left
        treeView = new ConnectionManagerTreeView(connections);
        teamworkTreeView = new ConnectionManagerTreeView(teamworkConnections);

        // One search for both trees; it narrows what the library on the left chose.
        searchField = new TextField();
        searchField.setPromptText(I18n.get("connManager.searchPrompt"));
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        // Register TreeView callbacks for local tree
        treeView.setOnCreateGroup(this::createNewGroup);
        treeView.setOnRenameGroup(this::renameGroup);
        treeView.setOnDeleteGroup(this::deleteGroup);
        treeView.setOnDoubleClick(() -> {
            List<ServerConnection> selected = treeView.getSelectedConnections();
            if (!selected.isEmpty()) {
                setResult(selected.get(0));
                close();
            }
        });
        treeView.setOnEditConnection(this::editConnection);
        treeView.setOnExportConnections(this::exportConnections);
        treeView.setOnDeleteConnections(this::deleteConnections);
        treeView.setOnExportGroup(this::exportGroup);
        treeView.setOnAddConnection(this::addConnection);
        treeView.setOnAssignTag(this::assignTagToConnections);
        treeView.setOnRemoveTag(this::removeTagFromConnections);
        treeView.setOnAssignTagToGroup(this::assignTagToGroup);
        treeView.setOnRemoveTagFromGroup(this::removeTagFromGroup);
        treeView.setGroupHostKeyCheckDisabledProbe(this::isGroupHostKeyCheckDisabled);
        treeView.setOnToggleGroupHostKeyCheck(this::toggleGroupHostKeyCheck);
        // Folder tab colors: only the local tree, as they never apply to teamwork connections.
        treeView.setOnEditGroupColor(this::editGroupColor);
        treeView.setOnSetGroupIsolation(this::setGroupIsolation);
        treeView.setGroupIsolationProbe(group -> {
            GlobalSettings settings = globalSettings();
            return settings != null ? settings.getConnectionGroupIsolationLevel(group.getPath()) : null;
        });
        treeView.setGroupColorProbe(this::groupColorOf);
        // A connection dragged into another folder takes that folder's color in its open tabs.
        treeView.setOnConnectionsMoved(MainWindow::refreshConnectionColorsInAllWindows);
        treeView.setOpenTabsProbe(this::openTabs);
        treeView.setFavoriteProbe(this::isFavorite);
        treeView.setOnToggleFavorite(this::toggleFavorites);
        treeView.setBadgeProbe(this::badgeNodeFor);
        treeView.setOsBadgeMenu(this::osBadgeMenu);

        // Teamwork tree: connect, export and delete (hide) only. Teamwork connections are read-only, as
        // korTTY never writes back to a source: no edit, no group ops, no dragging into another folder.
        teamworkTreeView.setReadOnlyConnections(true);
        teamworkTreeView.setOnDoubleClick(() -> {
            List<ServerConnection> selected = teamworkTreeView.getSelectedConnections();
            if (!selected.isEmpty()) {
                setResult(selected.get(0));
                close();
            }
        });
        teamworkTreeView.setOnExportConnections(this::exportConnections);
        teamworkTreeView.setOnDeleteConnections(this::deleteConnections);
        teamworkTreeView.setOpenTabsProbe(this::openTabs);
        teamworkTreeView.setFavoriteProbe(this::isFavorite);
        teamworkTreeView.setOnToggleFavorite(this::toggleFavorites);
        teamworkTreeView.setBadgeProbe(this::badgeNodeFor);

        addButton = new Button(I18n.get("connectionManager.add"));
        editButton = new Button(I18n.get("connectionManager.edit"));
        deleteButton = new Button(I18n.get("connectionManager.delete"));
        duplicateButton = new Button(I18n.get("connectionManager.duplicate"));
        exportButton = new Button(I18n.get("connectionManager.export"));
        importButton = new Button(I18n.get("connectionManager.import"));
        undoButton = new Button(I18n.get("connectionManager.undo"));
        createFolderButton = new Button(I18n.get("connectionManager.createFolder"));
        renameGroupButton = new Button(I18n.get("connectionManager.renameFolder"));
        refreshTeamworkButton = new Button(I18n.get("connectionManager.teamwork.refresh"));
        restoreTeamworkButton = new Button(I18n.get("connectionManager.teamwork.restore"));
        overviewButton = new Button(I18n.get("connectionManager.overview.button"));

        // Icon buttons in the style of the icon toolbar; the label stays as tooltip and for screen readers.
        toolButton(addButton, "addServer");
        toolButton(editButton, "edit");
        toolButton(duplicateButton, "duplicate");
        toolButton(deleteButton, "delete");
        toolButton(createFolderButton, "folderAdd");
        toolButton(renameGroupButton, "rename");
        toolButton(undoButton, "undo");
        toolButton(importButton, "importData");
        toolButton(exportButton, "exportData");
        toolButton(overviewButton, "exportOverview");
        toolButton(refreshTeamworkButton, "sync");
        toolButton(restoreTeamworkButton, "restore");

        editButton.setDisable(true);
        deleteButton.setDisable(true);
        duplicateButton.setDisable(true);
        exportButton.setDisable(true);
        undoButton.setDisable(true);
        renameGroupButton.setDisable(true);
        refreshTeamworkButton.setDisable(false);
        restoreTeamworkButton.setDisable(false);

        // Set undo button for treeView
        treeView.setUndoButton(undoButton);

        // Selection: buttons, inspector and the map follow the active tree.
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> selectionChanged());
        teamworkTreeView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> selectionChanged());
        // A rebuilt tree (filter, edit, move, import) changes the counts on the left and the map.
        treeView.rootProperty().addListener((obs, old, root) -> contentChanged());
        teamworkTreeView.rootProperty().addListener((obs, old, root) -> contentChanged());

        refreshTeamworkButton.setOnAction(e -> refreshTeamworkConnections());
        restoreTeamworkButton.setOnAction(e -> showRestoreTeamworkDialog());

        addButton.setOnAction(e -> addConnection());
        editButton.setOnAction(e -> editConnection(null));
        deleteButton.setOnAction(e -> {
            List<ServerConnection> selected = getActiveTreeView().getSelectedConnections();
            if (!selected.isEmpty()) {
                deleteConnections(selected);
            }
        });
        duplicateButton.setOnAction(e -> duplicateConnection());
        exportButton.setOnAction(e -> {
            List<ServerConnection> selected = getActiveTreeView().getSelectedConnections();
            if (!selected.isEmpty()) {
                exportConnections();
            } else if (isLocalTabActive()) {
                List<GroupPath> groups = treeView.getSelectedGroups();
                if (groups.size() == 1) {
                    exportGroup(groups.get(0));
                }
            }
        });
        importButton.setOnAction(e -> importConnections());
        overviewButton.setOnAction(e -> exportOverview());
        undoButton.setOnAction(e -> treeView.undoLastMove());
        createFolderButton.setOnAction(e -> {
            List<GroupPath> selected = treeView.getSelectedGroups();
            if (selected.size() == 1) {
                createNewGroup(selected.get(0));
            } else {
                createNewGroup(GroupPath.ROOT);
            }
        });
        renameGroupButton.setOnAction(e -> {
            List<GroupPath> selected = treeView.getSelectedGroups();
            if (!selected.isEmpty()) {
                renameGroup(selected.get(0));
            }
        });

        // Toolbar: grouped icon buttons as in the icon toolbar, the search on the right.
        teamworkToolGroup = toolGroup("connectionManager.section.teamwork", refreshTeamworkButton, restoreTeamworkButton);
        Label searchIcon = new Label(null, ToolbarIcons.create("search", themeText.textFillProperty(), 16));
        HBox searchBox = new HBox(6, searchIcon, searchField);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setPrefWidth(320);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        themeText.setManaged(false);
        themeText.setVisible(false);
        HBox toolbar = new HBox(8,
            toolGroup("connectionManager.section.connection", addButton, editButton, duplicateButton, deleteButton),
            toolGroup("connectionManager.section.folder", createFolderButton, renameGroupButton, undoButton),
            toolGroup("connectionManager.section.exchange", importButton, exportButton, overviewButton),
            teamworkToolGroup, toolbarSpacer, searchBox, themeText);
        toolbar.setAlignment(Pos.BOTTOM_LEFT);
        toolbar.getStyleClass().add("cm-toolbar");

        // Teamwork bar: auth dropdown (passwords/keys must not be in teamwork file)
        ComboBox<Object> teamworkAuthCombo = new ComboBox<>();
        teamworkAuthCombo.setPromptText(I18n.get("teamwork.auth.prompt"));
        teamworkAuthCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(teamworkAuthCombo, Priority.ALWAYS);
        buildTeamworkAuthCombo(teamworkAuthCombo);
        TextField teamworkUsernameField = new TextField();
        teamworkUsernameField.setPromptText(I18n.get("teamwork.auth.defaultUsername"));
        teamworkUsernameField.setMaxWidth(200);
        teamworkUsernameField.setVisible(false);
        teamworkUsernameField.setManaged(false);
        Label teamworkAuthLabel = new Label(I18n.get("teamwork.auth.forAll"));
        teamworkAuthBar = new HBox(10);
        teamworkAuthBar.setAlignment(Pos.CENTER_LEFT);
        teamworkAuthBar.setPadding(new Insets(4, 10, 8, 10));
        teamworkAuthBar.getChildren().addAll(teamworkAuthLabel, teamworkAuthCombo, teamworkUsernameField);
        teamworkAuthCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressTeamworkAuthSave) return;
            boolean needsUsername = newVal instanceof SSHKey || newVal == TEAMWORK_AUTH_TEMPORARY_KEY;
            teamworkUsernameField.setVisible(needsUsername);
            teamworkUsernameField.setManaged(needsUsername);
            saveTeamworkAuthSelection(teamworkAuthCombo.getValue(), teamworkUsernameField.getText());
        });
        teamworkUsernameField.textProperty().addListener((obs, o, n) -> {
            if (suppressTeamworkAuthSave) return;
            saveTeamworkAuthSelection(teamworkAuthCombo.getValue(), n);
        });

        // Middle: what the library chose, as list or map.
        listViewButton = new ToggleButton(I18n.get("connectionManager.view.list"),
            ToolbarIcons.create("viewList", themeText.textFillProperty(), 16));
        mapViewButton = new ToggleButton(I18n.get("connectionManager.view.map"),
            ToolbarIcons.create("viewMap", themeText.textFillProperty(), 16));
        ToggleGroup viewToggles = new ToggleGroup();
        listViewButton.setToggleGroup(viewToggles);
        mapViewButton.setToggleGroup(viewToggles);
        listViewButton.setSelected(true);
        listViewButton.setOnAction(e -> setMapMode(false));
        mapViewButton.setOnAction(e -> setMapMode(true));
        listViewButton.getStyleClass().addAll("cm-view", "left-pill");
        mapViewButton.getStyleClass().addAll("cm-view", "right-pill");
        scopeTitle.getStyleClass().add("cm-scope-title");
        scopeCount.setStyle("-fx-opacity: 0.7;");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox centerHeader = new HBox(10, scopeTitle, scopeCount, headerSpacer, new HBox(listViewButton, mapViewButton));
        centerHeader.setAlignment(Pos.CENTER_LEFT);
        centerHeader.getStyleClass().add("cm-center-header");
        mapView.setOnSelect(this::selectFromMap);
        mapView.setPdfScope(this::scopeTitle);
        mapView.setOnOpen(connection -> {
            setResult(connection);
            close();
        });
        mapView.setOnMove((connection, group) -> {
            if (treeView.moveConnection(connection, group)) {
                treeView.selectConnection(connection);
            }
        });
        StackPane centerStack = new StackPane(treeView, teamworkTreeView, mapView);
        VBox.setVgrow(centerStack, Priority.ALWAYS);
        VBox centerPane = new VBox(centerHeader, teamworkAuthBar, centerStack);

        // Left: the library; right: the inspector.
        sidebarItems.getStyleClass().add("cm-sidebar-items");
        sidebarItems.setPadding(new Insets(6, 6, 10, 6));
        ScrollPane sidebar = new ScrollPane(sidebarItems);
        sidebar.setFitToWidth(true);
        sidebar.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebar.getStyleClass().add("cm-sidebar");
        sidebar.setMinWidth(170);
        inspector.setMinWidth(250);
        inspector.setOnConnect(connection -> {
            setResult(connection);
            close();
        });
        inspector.setOnEdit(this::editConnection);
        inspector.setOnSftp(connection -> {
            sftpRequest = connection;
            setResult(null);
            close();
        });
        inspector.setOnDuplicate(connection -> duplicateConnection());
        inspector.setOnToggleFavorite(connection -> toggleFavorites(List.of(connection)));
        inspector.setOnRename(this::renameConnection);
        inspector.setOnRetag((connection, tag) -> {
            applyTag(List.of(connection), tag);
            treeView.selectConnection(connection);
        });
        inspector.setOnRenameGroup(this::renameGroup);
        inspector.setBadgePicker(this::badgePicker);
        inspector.setOnEditGroupColor(this::editGroupColor);
        inspector.setOnCreateSubgroup(this::createNewGroup);

        SplitPane split = new SplitPane(sidebar, centerPane, inspector);
        split.setDividerPositions(0.19, 0.71);
        SplitPane.setResizableWithParent(sidebar, false);
        SplitPane.setResizableWithParent(inspector, false);
        split.getStyleClass().add("cm-split");

        BorderPane content = new BorderPane();
        content.setTop(toolbar);
        content.setCenter(split);
        content.getStyleClass().add("cm-root");

        getDialogPane().setContent(content);
        getDialogPane().getStylesheets().add(java.util.Objects.requireNonNull(
            getClass().getResource("/styles/connection-manager.css")).toExternalForm());
        getDialogPane().setPrefSize(1180, 740);
        getDialogPane().setMinSize(860, 520);

        rebuildSidebar();
        applyFilters();

        // Dialog buttons
        connectButtonType = new ButtonType(I18n.get("quickConnect.connect"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(connectButtonType, ButtonType.CLOSE);

        Button connectButton = (Button) getDialogPane().lookupButton(connectButtonType);
        connectButton.setDisable(true);
        updateButtonState();

        setOnShown(e -> {
            refreshTeamworkConnections();
            buildTeamworkAuthCombo(teamworkAuthCombo);
            applyTeamworkAuthFromSettings(teamworkAuthCombo, teamworkUsernameField);
            updateButtonState();
            searchField.requestFocus();
        });

        TeamworkSyncService syncService = app.getTeamworkSyncService();
        if (syncService != null) {
            teamworkCacheUpdateListener = () -> javafx.application.Platform.runLater(this::refreshTeamworkConnections);
            syncService.addCacheUpdateListener(teamworkCacheUpdateListener);
        }

        // Defensive cleanup: remove listener on hidden, close-request, and result converter
        Runnable cleanupListener = () -> {
            if (app.getTeamworkSyncService() != null && teamworkCacheUpdateListener != null) {
                app.getTeamworkSyncService().removeCacheUpdateListener(teamworkCacheUpdateListener);
            }
        };
        setOnHidden(e -> cleanupListener.run());
        setOnCloseRequest(e -> cleanupListener.run());

        setResultConverter(dialogButton -> {
            try {
                if (dialogButton == connectButtonType) {
                    List<ServerConnection> selected = getActiveTreeView().getSelectedConnections();
                    if (!selected.isEmpty()) {
                        return selected.get(0);
                    }
                }
                return null;
            } finally {
                cleanupListener.run();
            }
        });
    }

    private ConnectionManagerTreeView getActiveTreeView() {
        return isLocalTabActive() ? treeView : teamworkTreeView;
    }

    /** Whether the local connections are shown, i.e. anything but Teamwork is chosen on the left. */
    private boolean isLocalTabActive() {
        return scope.kind() != ScopeKind.TEAMWORK;
    }

    // ----- Layout helpers -----

    /** Turns {@code button} into an icon button of the toolbar; its label stays as tooltip and accessible text. */
    private void toolButton(Button button, String glyph) {
        button.setGraphic(ToolbarIcons.create(glyph, themeText.textFillProperty(), 20));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setTooltip(new Tooltip(button.getText()));
        button.setAccessibleText(button.getText());
        button.getStyleClass().add("cm-tool");
    }

    /** A titled group of toolbar buttons, as the icon toolbar groups its actions. */
    private static VBox toolGroup(String headingKey, Button... buttons) {
        Label heading = new Label(I18n.get(headingKey));
        heading.getStyleClass().add("cm-tool-heading");
        HBox row = new HBox(2, buttons);
        VBox group = new VBox(1, heading, row);
        group.getStyleClass().add("cm-tool-group");
        return group;
    }

    // ----- Library (left), list or map (middle), inspector (right) -----

    private enum ScopeKind { ALL, FAVORITES, RECENT, OPEN, GROUP, TAG, TEAMWORK }

    /** What the library on the left chose: a smart list, a folder or tag ({@code value}), or the teamwork connections. */
    private record Scope(ScopeKind kind, String value) {
        static final Scope ALL = new Scope(ScopeKind.ALL, null);
    }

    /** How many days the inspector's usage bars cover. */
    private static final int USAGE_DAYS = 14;

    /** The connection whose SFTP Manager the user asked for; opened by the main window once this dialog closed. */
    private ServerConnection sftpRequest;

    /** The connection to open the SFTP Manager for after the dialog closed, or null. */
    public ServerConnection getSftpRequest() {
        return sftpRequest;
    }

    /** How many connections the "Recently used" list shows. */
    private static final int RECENT_LIMIT = 15;

    private void setScope(Scope newScope) {
        scope = newScope;
        applyFilters();
        rebuildSidebar();
    }

    private void setMapMode(boolean map) {
        mapMode = map;
        listViewButton.setSelected(!map);
        mapViewButton.setSelected(map);
        refreshView();
    }

    /** Applies the library's choice and the search to the tree in the middle. */
    private void applyFilters() {
        Predicate<ServerConnection> search = searchPredicate(searchField.getText());
        if (isLocalTabActive()) {
            // The general lists show the teamwork connections too, read-only in a branch of their own;
            // a folder is always a local one.
            treeView.setSharedConnections(scope.kind() == ScopeKind.GROUP ? List.of() : teamworkConnections,
                I18n.get("connectionManager.tab.teamwork"));
            Predicate<ServerConnection> chosen = scopePredicate();
            treeView.filterTree(chosen == null ? search : search == null ? chosen : chosen.and(search));
        } else {
            teamworkTreeView.filterTree(search);
        }
        refreshView();
    }

    /**
     * The connections whose name, host or tag contain {@code text}, ignoring case; with {@code *} the
     * whole name, host or tag must match the pattern. Null for no text.
     */
    static Predicate<ServerConnection> searchPredicate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String searchText = text.trim();
        Pattern pattern = null;
        if (searchText.contains("*")) {
            StringBuilder regex = new StringBuilder();
            for (String part : searchText.split("\\*", -1)) {
                if (regex.length() > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        }
        final Pattern finalPattern = pattern;
        final String lower = searchText.toLowerCase();
        return connection -> {
            for (String value : new String[] {connection.getName(), connection.getHost(), connection.getTag()}) {
                if (value == null) {
                    continue;
                }
                if (finalPattern != null ? finalPattern.matcher(value).matches() : value.toLowerCase().contains(lower)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** The local connections the library's choice shows, or null for all of them. */
    private Predicate<ServerConnection> scopePredicate() {
        // The value is taken now: the tree keeps this filter and runs it again after later changes.
        String value = scope.value();
        return switch (scope.kind()) {
            case FAVORITES -> this::isFavorite;
            case RECENT -> {
                java.util.Set<ServerConnection> recent = new java.util.HashSet<>(recentConnections());
                yield recent::contains;
            }
            case OPEN -> connection -> openTabs(connection) > 0;
            case GROUP -> {
                GroupPath folder = new GroupPath(value);
                yield connection -> connection.getGroup() != null
                    && (new GroupPath(connection.getGroup()).equals(folder) || new GroupPath(connection.getGroup()).isChildOf(folder));
            }
            case TAG -> connection -> value.equalsIgnoreCase(connection.getTag());
            default -> null;
        };
    }

    /** The local connections used most recently, newest first. */
    private List<ServerConnection> recentConnections() {
        return java.util.stream.Stream.concat(connections.stream(), teamworkConnections.stream())
            .filter(connection -> !connection.isPlaceholder() && connection.getLastUsed() > 0)
            .sorted(java.util.Comparator.comparingLong(ServerConnection::getLastUsed).reversed())
            .limit(RECENT_LIMIT)
            .toList();
    }

    /** Shows the active tree or the map, the scope's title and count, and the teamwork bar. */
    private void refreshView() {
        boolean local = isLocalTabActive();
        treeView.setVisible(local && !mapMode);
        teamworkTreeView.setVisible(!local && !mapMode);
        mapView.setVisible(mapMode);
        teamworkAuthBar.setVisible(!local);
        teamworkAuthBar.setManaged(!local);
        scopeTitle.setText(scopeTitle());
        scopeCount.setText(I18n.get("connectionManager.count", shownConnections().size()));
        if (mapMode) {
            refreshMap();
        }
        updateButtonState();
        updateInspector();
    }

    private String scopeTitle() {
        return switch (scope.kind()) {
            case FAVORITES -> I18n.get("connectionManager.library.favorites");
            case RECENT -> I18n.get("connectionManager.library.recent");
            case OPEN -> I18n.get("connectionManager.library.open");
            case GROUP -> scope.value().replace(GroupPath.SEPARATOR, " / ");
            case TAG -> I18n.get("connectionManager.tagTitle", scope.value());
            case TEAMWORK -> I18n.get("connectionManager.tab.teamwork");
            default -> I18n.get("connectionManager.library.all");
        };
    }

    /** The connections the middle shows: those of the active tree that pass its filter, without folder placeholders. */
    private List<ServerConnection> shownConnections() {
        ConnectionManagerTreeView tree = getActiveTreeView();
        Predicate<ServerConnection> filter = tree.getFilter();
        java.util.stream.Stream<ServerConnection> all = isLocalTabActive()
            ? java.util.stream.Stream.concat(connections.stream(), teamworkConnections.stream().filter(treeView::isShared))
            : teamworkConnections.stream();
        return all.filter(connection -> !connection.isPlaceholder() && (filter == null || filter.test(connection)))
            .toList();
    }

    private void refreshMap() {
        boolean local = isLocalTabActive();
        mapView.show(shownConnections(), local ? this::inheritedGroupColor : group -> null, this::openTabs,
            connection -> !local || isSharedConnection(connection), this::badgeNodeFor);
        mapView.setSelection(getActiveTreeView().getSelectedConnections());
    }

    /** After a tree was rebuilt: new counts on the left, and the map and inspector again. */
    private void contentChanged() {
        if (teamworkAuthBar == null) {
            return; // still being built
        }
        rebuildSidebar();
        scopeCount.setText(I18n.get("connectionManager.count", shownConnections().size()));
        if (mapMode) {
            refreshMap();
        }
        updateInspector();
    }

    private void selectionChanged() {
        updateButtonState();
        updateInspector();
        if (mapMode) {
            mapView.setSelection(getActiveTreeView().getSelectedConnections());
        }
    }

    /** Selects a connection clicked on the map in the list, so the buttons and the inspector follow. */
    private void selectFromMap(ServerConnection connection) {
        if (!getActiveTreeView().selectConnection(connection)) {
            inspector.showConnection(connection, factsFor(connection, isSharedConnection(connection)));
            mapView.setSelection(List.of(connection));
        }
    }

    private void updateInspector() {
        ConnectionManagerTreeView tree = getActiveTreeView();
        List<ServerConnection> selected = tree.getSelectedConnections();
        List<GroupPath> groups = isLocalTabActive() ? treeView.getSelectedGroups() : List.of();
        if (selected.size() == 1) {
            inspector.showConnection(selected.get(0), factsFor(selected.get(0), isSharedConnection(selected.get(0))));
        } else if (selected.isEmpty() && groups.size() == 1) {
            GroupPath group = groups.get(0);
            GlobalSettings settings = globalSettings();
            de.kortty.isolation.IsolationLevel level = settings != null
                ? settings.getConnectionGroupIsolationLevel(group.getPath()) : null;
            inspector.showGroup(group, new ConnectionInspectorPane.FolderFacts(connectionsInGroup(group).size(),
                inheritedGroupColor(group.getPath()),
                level != null ? IsolationConnectionSupport.levelName(level) : I18n.get("connectionManager.inspector.isolation.inherit"),
                isGroupHostKeyCheckDisabled(group)), false);
        } else {
            inspector.showEmpty();
        }
    }

    /** What the inspector says about {@code connection} beyond the connection itself. */
    private ConnectionInspectorPane.Facts factsFor(ServerConnection connection, boolean readOnly) {
        GlobalSettings settings = globalSettings();
        String lastUsed = connection.getLastUsed() > 0
            ? java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM,
                java.time.format.FormatStyle.SHORT).format(java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(connection.getLastUsed()), java.time.ZoneId.systemDefault()))
            : I18n.get("connectionManager.inspector.never");
        OsBadgeSupport.Resolved badge = OsBadgeSupport.resolve(connection.getOsBadge(), settings);
        return new ConnectionInspectorPane.Facts(isFavorite(connection), openTabs(connection), readOnly,
            readOnly ? null : inheritedGroupColor(connection.getGroup()), authDescription(connection),
            isolationDescription(connection, settings), hostKeyDescription(connection, settings),
            jumpDescription(connection), lastUsed, teamworkSourceName(connection, settings),
            connection.getUsageByDay(java.time.LocalDate.now(), USAGE_DAYS),
            ConnectionRowGraphics.relativeTime(connection.getLastUsed(), System.currentTimeMillis()),
            badge != null ? OsBadgeSupport.badgeNode(badge, 24) : null, badge != null ? badge.name() : null);
    }

    /** Where a teamwork connection comes from: its source's location, else the teamwork tab's name. */
    private static String teamworkSourceName(ServerConnection connection, GlobalSettings settings) {
        if (settings != null && connection.getTeamworkSourceId() != null) {
            for (de.kortty.model.TeamworkSourceConfig source : settings.getTeamworkSources()) {
                if (connection.getTeamworkSourceId().equals(source.getId()) && source.getLocation() != null) {
                    return source.getLocation();
                }
            }
        }
        return I18n.get("connectionManager.tab.teamwork");
    }

    private String authDescription(ServerConnection connection) {
        if (connection.getProtocol() == de.kortty.model.ConnectionProtocol.LOCAL_SHELL) {
            return I18n.get("connectionManager.inspector.auth.none");
        }
        if (connection.getTemporaryKeyContent() != null || connection.isTemporaryKeyPermanent()) {
            return I18n.get("connEdit.authTempKey");
        }
        if (connection.getCredentialId() != null && credentialManager != null) {
            var credential = credentialManager.findCredentialById(connection.getCredentialId());
            if (credential.isPresent()) {
                return I18n.get("connectionManager.inspector.auth.credential", credential.get().getName());
            }
        }
        if (connection.getSshKeyId() != null && app.getSSHKeyManager() != null) {
            var key = app.getSSHKeyManager().findKeyById(connection.getSshKeyId());
            if (key.isPresent()) {
                return I18n.get("connectionManager.inspector.auth.key", key.get().getName());
            }
        }
        if (connection.isTeamworkConnection() && connection.getCredentialId() == null && connection.getSshKeyId() == null) {
            return I18n.get("connectionManager.inspector.auth.team");
        }
        if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
            return I18n.get("connEdit.authKey");
        }
        if (connection.getAuthMethod() == de.kortty.model.AuthMethod.KEYBOARD_INTERACTIVE) {
            return I18n.get("connectionManager.inspector.auth.keyboard");
        }
        return I18n.get(connection.getEncryptedPassword() != null
            ? "connectionManager.inspector.auth.savedPassword" : "connectionManager.inspector.auth.askPassword");
    }

    private static String isolationDescription(ServerConnection connection, GlobalSettings settings) {
        if (connection.getIsolationLevel() != null) {
            return IsolationConnectionSupport.levelName(connection.getIsolationLevel());
        }
        if (settings == null) {
            return I18n.get("connectionManager.inspector.isolation.inherit");
        }
        if (connection.getGroup() != null) {
            GroupPath path = new GroupPath(connection.getGroup());
            while (path != null && !path.isRoot()) {
                de.kortty.isolation.IsolationLevel level = settings.getConnectionGroupIsolationLevel(path.getPath());
                if (level != null) {
                    return I18n.get("connectionManager.inspector.isolation.folder", IsolationConnectionSupport.levelName(level));
                }
                path = path.getParent();
            }
        }
        de.kortty.isolation.IsolationLevel global = settings.getConnectionIsolationDefault();
        return global != null
            ? I18n.get("connectionManager.inspector.isolation.global", IsolationConnectionSupport.levelName(global))
            : I18n.get("connectionManager.inspector.isolation.inherit");
    }

    private static String hostKeyDescription(ServerConnection connection, GlobalSettings settings) {
        Boolean own = connection.getDisableHostKeyCheck();
        if (own != null) {
            return I18n.get(own ? "connectionManager.inspector.hostKey.off" : "connectionManager.inspector.hostKey.on");
        }
        if (settings != null && connection.getGroup() != null && settings.isHostKeyCheckDisabledForGroup(connection.getGroup())) {
            return I18n.get("connectionManager.inspector.hostKey.offFolder");
        }
        if (settings != null && settings.isHostKeyCheckDisabledForAllConnections()) {
            return I18n.get("connectionManager.inspector.hostKey.offGlobal");
        }
        return I18n.get("connectionManager.inspector.hostKey.on");
    }

    /** The jump server, named after its saved connection when there is one. */
    private String jumpDescription(ServerConnection connection) {
        String target = ConnectionRowGraphics.jumpTarget(connection);
        if (target == null) {
            return null;
        }
        String host = connection.getJumpServer().getHost().trim();
        for (ServerConnection candidate : isLocalTabActive() ? connections : teamworkConnections) {
            if (candidate != connection && !candidate.isPlaceholder() && candidate.getHost() != null
                    && candidate.getHost().trim().equalsIgnoreCase(host)) {
                return candidate.getName() + " (" + target + ")";
            }
        }
        return target;
    }

    /** The tab color a connection in {@code group} shows: the folder's own, else the nearest one above; null without. */
    private String inheritedGroupColor(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        var inherited = ConnectionGroupColors.inherited(group, path -> groupColorOf(new GroupPath(path)));
        return inherited != null ? inherited.hex() : null;
    }

    /** Rebuilds the library on the left: smart lists, folders, tags and the teamwork entry, with their counts. */
    private void rebuildSidebar() {
        refreshOpenTabCounts();
        List<javafx.scene.Node> items = new ArrayList<>();
        items.add(sideHeading("connectionManager.library"));
        List<ServerConnection> everything = java.util.stream.Stream.concat(connections.stream(), teamworkConnections.stream())
            .filter(connection -> !connection.isPlaceholder()).toList();
        items.add(sideItem(Scope.ALL, "connectionManager.library.all", "viewList", null, everything.size()));
        items.add(sideItem(new Scope(ScopeKind.FAVORITES, null), "connectionManager.library.favorites", "star", null,
            everything.stream().filter(this::isFavorite).count()));
        items.add(sideItem(new Scope(ScopeKind.RECENT, null), "connectionManager.library.recent", "clock", null,
            recentConnections().size()));
        items.add(sideItem(new Scope(ScopeKind.OPEN, null), "connectionManager.library.open", "tab", null,
            everything.stream().filter(connection -> openTabs(connection) > 0).count()));

        java.util.TreeSet<String> folders = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        java.util.TreeSet<String> tags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ServerConnection connection : connections) {
            if (connection.getGroup() != null && !connection.getGroup().isBlank()) {
                GroupPath path = new GroupPath(connection.getGroup());
                while (path != null && !path.isRoot()) {
                    folders.add(path.getPath());
                    path = path.getParent();
                }
            }
            if (!connection.isPlaceholder() && connection.getTag() != null && !connection.getTag().isBlank()) {
                tags.add(connection.getTag().trim());
            }
        }
        for (ServerConnection connection : teamworkConnections) {
            if (connection.getTag() != null && !connection.getTag().isBlank()) {
                tags.add(connection.getTag().trim());
            }
        }
        if (!folders.isEmpty()) {
            items.add(sideHeading("connectionManager.folders"));
            for (String folder : folders) {
                GroupPath path = new GroupPath(folder);
                ToggleButton item = sideItem(new Scope(ScopeKind.GROUP, folder), null, "folder",
                    inheritedGroupColor(folder), connectionsInGroup(path).size());
                ((Label) ((HBox) item.getGraphic()).getChildren().get(1)).setText(path.getName());
                // Inline, as the stylesheet's padding would win over a value set in code.
                item.setStyle("-fx-padding: 4 8 4 " + (8 + 16 * (path.getDepth() - 1)) + ";");
                items.add(item);
            }
        }
        if (!tags.isEmpty()) {
            items.add(sideHeading("connectionManager.tags"));
            javafx.scene.layout.FlowPane chips = new javafx.scene.layout.FlowPane(6, 6);
            chips.setPadding(new Insets(2, 6, 4, 6));
            for (String tag : tags) {
                // The text is a label in the graphic, which follows the theme like the other library entries;
                // a toggle button's own text takes a colour from its (here transparent) background.
                Label text = new Label(tag);
                HBox graphic = new HBox(5, ToolbarIcons.create("tag", text.textFillProperty(), 12), text);
                graphic.setAlignment(Pos.CENTER_LEFT);
                ToggleButton chip = new ToggleButton(null, graphic);
                chip.setAccessibleText(tag);
                chip.getStyleClass().add("cm-tag");
                Scope tagScope = new Scope(ScopeKind.TAG, tag);
                chip.setSelected(tagScope.equals(scope));
                chip.setOnAction(e -> setScope(chip.isSelected() ? tagScope : Scope.ALL));
                chips.getChildren().add(chip);
            }
            items.add(chips);
        }
        items.add(sideHeading("connectionManager.section.teamwork"));
        items.add(sideItem(new Scope(ScopeKind.TEAMWORK, null), "connectionManager.tab.teamwork", "teamwork", null,
            teamworkConnections.size()));
        sidebarItems.getChildren().setAll(items);
    }

    private static Label sideHeading(String key) {
        Label heading = new Label(I18n.get(key));
        heading.getStyleClass().add("cm-side-heading");
        return heading;
    }

    /** One entry of the library; choosing it again keeps it chosen. */
    private ToggleButton sideItem(Scope itemScope, String key, String glyph, String coreColor, long count) {
        Label name = new Label(key != null ? I18n.get(key) : "");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);
        Label number = new Label(String.valueOf(count));
        number.setStyle("-fx-opacity: 0.65; -fx-font-size: 0.9em;");
        number.setMinWidth(Region.USE_PREF_SIZE);
        HBox graphic = new HBox(8, ToolbarIcons.create(glyph, name.textFillProperty(), 17, coreColor), name, number);
        graphic.setAlignment(Pos.CENTER_LEFT);
        ToggleButton item = new ToggleButton(null, graphic);
        item.getStyleClass().add("cm-side-item");
        item.setMaxWidth(Double.MAX_VALUE);
        item.setToggleGroup(scopeToggles);
        item.setSelected(itemScope.equals(scope));
        item.setAccessibleText((key != null ? I18n.get(key) : itemScope.value()) + ", " + count);
        graphic.prefWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
            () -> item.getWidth() - item.getPadding().getLeft() - item.getPadding().getRight() - 2,
            item.widthProperty(), item.paddingProperty()));
        item.setOnAction(e -> {
            if (itemScope.equals(scope)) {
                item.setSelected(true);
                return;
            }
            setScope(itemScope);
        });
        return item;
    }

    // ----- Favorites, open tabs, quick edits -----

    /** Whether {@code connection} is a teamwork connection (read-only), wherever it is shown. */
    private boolean isSharedConnection(ServerConnection connection) {
        for (ServerConnection shared : teamworkConnections) {
            if (shared == connection) {
                return true;
            }
        }
        return false;
    }

    private boolean isFavorite(ServerConnection connection) {
        GlobalSettings settings = globalSettings();
        return connection != null && settings != null && settings.isFavoriteConnection(connection.getId());
    }

    /**
     * Marks {@code targets} as favorites, or unmarks them when all of them already are, and saves the
     * global settings; a failed save puts the previous favorites back.
     */
    private void toggleFavorites(List<ServerConnection> targets) {
        var gsm = app.getGlobalSettingsManager();
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null || targets.isEmpty()) {
            return;
        }
        List<String> previous = new ArrayList<>(settings.getFavoriteConnectionIds());
        List<String> ids = new ArrayList<>(previous);
        boolean unmark = targets.stream().allMatch(this::isFavorite);
        for (ServerConnection connection : targets) {
            if (unmark) {
                ids.remove(connection.getId());
            } else if (!ids.contains(connection.getId())) {
                ids.add(connection.getId());
            }
        }
        settings.setFavoriteConnectionIds(ids);
        try {
            gsm.save();
        } catch (Exception e) {
            settings.setFavoriteConnectionIds(previous);
            logger.error("Could not save the favorite connections", e);
            return;
        }
        ServerConnection only = targets.size() == 1 ? targets.get(0) : null;
        getActiveTreeView().refreshPreservingFilter();
        if (only != null) {
            getActiveTreeView().selectConnection(only);
        }
    }

    // ----- Overview export -----

    /** Opens the overview export for the selection, the shown connections or all of them. */
    private void exportOverview() {
        GlobalSettings settings = globalSettings();
        List<ServerConnection> all = java.util.stream.Stream.concat(connections.stream(), teamworkConnections.stream())
            .filter(connection -> !connection.isPlaceholder()).toList();
        ConnectionOverviewExport.Context context = new ConnectionOverviewExport.Context(
            KorTTYApplication.getAppVersion(), java.time.Instant.now(),
            id -> credentialManager != null
                ? credentialManager.findCredentialById(id).map(StoredCredential::getName).orElse(null) : null,
            id -> app.getSSHKeyManager() != null
                ? app.getSSHKeyManager().findKeyById(id).map(SSHKey::getName).orElse(null) : null,
            this::isFavorite,
            connection -> {
                OsBadgeSupport.Resolved badge = OsBadgeSupport.resolve(connection.getOsBadge(), settings);
                return badge != null ? badge.name() : null;
            },
            connection -> isSharedConnection(connection) ? teamworkSourceName(connection, settings) : null);
        new ConnectionOverviewExportDialog(childOwner(), getActiveTreeView().getSelectedConnections(),
            shownConnections(), all, context).showAndWait();
    }

    // ----- OS badges -----

    /** The OS badge of {@code connection} for its tile, or null. */
    private javafx.scene.Node badgeNodeFor(ServerConnection connection) {
        OsBadgeSupport.Resolved badge = OsBadgeSupport.resolve(connection.getOsBadge(), globalSettings());
        return badge != null ? OsBadgeSupport.badgeNode(badge, 15) : null;
    }

    /** The context menu's "OS symbol" entries for {@code targets}: none, the badges by family, the own ones' manager. */
    private Menu osBadgeMenu(List<ServerConnection> targets) {
        Menu menu = new Menu();
        MenuItem none = new MenuItem(I18n.get("connectionManager.os.none"));
        none.setOnAction(e -> setOsBadge(targets, null));
        menu.getItems().addAll(none, new SeparatorMenuItem());
        Map<String, Menu> families = new java.util.LinkedHashMap<>();
        for (OsBadgeSupport.Resolved badge : OsBadgeSupport.all(globalSettings())) {
            Menu family = families.computeIfAbsent(OsBadgeSupport.familyName(badge), Menu::new);
            MenuItem item = new MenuItem(badge.name(), OsBadgeSupport.badgeNode(badge, 16));
            item.setOnAction(e -> setOsBadge(targets, badge.id()));
            family.getItems().add(item);
        }
        menu.getItems().addAll(families.values());
        MenuItem manage = new MenuItem(I18n.get("connectionManager.os.manage"));
        manage.setOnAction(e -> openBadgeManager());
        menu.getItems().addAll(new SeparatorMenuItem(), manage);
        if (targets.isEmpty()) {
            menu.getItems().forEach(item -> item.setDisable(item != manage));
        }
        return menu;
    }

    /** The inspector's OS symbol picker for {@code connection}, with a button to the user's own symbols. */
    private javafx.scene.Node badgePicker(ServerConnection connection) {
        ComboBox<OsBadgeSupport.Resolved> picker = OsBadgeSupport.picker(connection.getOsBadge(), globalSettings());
        picker.valueProperty().addListener((obs, old, badge) ->
            setOsBadge(List.of(connection), badge != null ? badge.id() : null));
        Button manage = new Button(I18n.get("connectionManager.os.manage"));
        manage.setGraphic(ToolbarIcons.create("edit", manage.textFillProperty(), 15));
        manage.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        manage.setTooltip(new Tooltip(I18n.get("connectionManager.os.manage")));
        manage.setAccessibleText(I18n.get("connectionManager.os.manage"));
        manage.setOnAction(e -> openBadgeManager());
        HBox box = new HBox(6, picker, manage);
        HBox.setHgrow(picker, Priority.ALWAYS);
        return box;
    }

    /** Gives the local connections among {@code targets} the badge {@code id} (null for none) and saves. */
    private void setOsBadge(List<ServerConnection> targets, String id) {
        List<ServerConnection> local = targets.stream().filter(conn -> localEditIndex(connections, conn) >= 0).toList();
        if (local.isEmpty()) {
            return;
        }
        for (ServerConnection connection : local) {
            connection.setOsBadge(id);
            configManager.updateConnection(connection);
        }
        treeView.refreshPreservingFilter();
        saveConnections();
        if (local.size() == 1) {
            treeView.selectConnection(local.get(0));
        }
    }

    private void openBadgeManager() {
        new OsBadgeManagerDialog(childOwner(), app.getGlobalSettingsManager()).showAndWait();
        treeView.refreshPreservingFilter();
        teamworkTreeView.refreshPreservingFilter();
    }

    /** Renames a local connection from the inspector and saves. */
    private void renameConnection(ServerConnection connection, String name) {
        if (localEditIndex(connections, connection) < 0 || name == null || name.isBlank()) {
            return;
        }
        connection.setName(name);
        configManager.updateConnection(connection);
        treeView.refreshPreservingFilter();
        saveConnections();
        treeView.selectConnection(connection);
    }

    /** Counts the open tabs of every window by connection id, for the dots and the "Open" list. */
    private void refreshOpenTabCounts() {
        openTabCounts = MainWindow.openTabCountsByConnectionId();
    }

    private int openTabs(ServerConnection connection) {
        return connection != null && connection.getId() != null ? openTabCounts.getOrDefault(connection.getId(), 0) : 0;
    }

    private void updateButtonState() {
        boolean local = isLocalTabActive();
        List<ServerConnection> selectedConnections = getActiveTreeView().getSelectedConnections();
        List<GroupPath> selectedGroups = local ? treeView.getSelectedGroups() : List.of();
        
        boolean hasSingleConnection = selectedConnections.size() == 1;
        boolean hasConnections = !selectedConnections.isEmpty();
        boolean hasSingleGroup = selectedGroups.size() == 1 && selectedConnections.isEmpty();
        boolean canExport = hasConnections || (local && hasSingleGroup);
        
        addButton.setDisable(!local);
        editButton.setDisable(!local || !hasSingleConnection);
        deleteButton.setDisable(!hasConnections);
        duplicateButton.setDisable(!local || !hasSingleConnection);
        exportButton.setDisable(!canExport);
        undoButton.setDisable(!local || !treeView.isUndoAvailable());
        createFolderButton.setDisable(!local);
        renameGroupButton.setDisable(!local || !hasSingleGroup);
        importButton.setDisable(!local);
        // A teamwork connection shown in the general lists stays read-only.
        if (selectedConnections.stream().anyMatch(this::isSharedConnection)) {
            editButton.setDisable(true);
            duplicateButton.setDisable(true);
        }
        
        Button connectButton = connectButtonType != null ? (Button) getDialogPane().lookupButton(connectButtonType) : null;
        if (connectButton != null) {
            connectButton.setDisable(selectedConnections.isEmpty());
        }
    }
    
    private void refreshTeamworkConnections() {
        TeamworkSyncService syncService = app.getTeamworkSyncService();
        if (syncService == null) return;
        teamworkConnections.clear();
        java.util.Set<String> deletedIds = app.getTeamworkRecycleBinService() != null
            ? app.getTeamworkRecycleBinService().getDeletedIds()
            : java.util.Set.of();
        syncService.getTeamworkConnections().stream()
            .filter(c -> !deletedIds.contains(c.getId()))
            .forEach(teamworkConnections::add);
        teamworkTreeView.refreshPreservingFilter();
        treeView.refreshPreservingFilter();
    }
    
    private void buildTeamworkAuthCombo(ComboBox<Object> combo) {
        Object current = combo.getValue();
        combo.getItems().clear();
        combo.getItems().add(null); // None
        if (credentialManager != null) {
            combo.getItems().addAll(credentialManager.getAllCredentials());
        }
        if (app.getSSHKeyManager() != null) {
            combo.getItems().addAll(app.getSSHKeyManager().getAllKeys());
        }
        combo.getItems().add(TEAMWORK_AUTH_TEMPORARY_KEY);
        combo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else if (item instanceof StoredCredential c) {
                    setText(c.getName() + " (" + (c.getUsername() != null ? c.getUsername() : "") + ")");
                } else if (item instanceof SSHKey k) {
                    setText(I18n.get("teamwork.auth.sshKey") + ": " + k.getName());
                } else if (item == TEAMWORK_AUTH_TEMPORARY_KEY) {
                    setText(I18n.get("teamwork.auth.temporaryKey"));
                } else {
                    setText(String.valueOf(item));
                }
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(I18n.get("teamwork.auth.none"));
                } else if (item instanceof StoredCredential c) {
                    setText(c.getName() + " (" + (c.getUsername() != null ? c.getUsername() : "") + ")");
                } else if (item instanceof SSHKey k) {
                    setText(I18n.get("teamwork.auth.sshKey") + ": " + k.getName());
                } else if (item == TEAMWORK_AUTH_TEMPORARY_KEY) {
                    setText(I18n.get("teamwork.auth.temporaryKey"));
                } else {
                    setText(String.valueOf(item));
                }
            }
        });
        if (current != null && combo.getItems().contains(current)) {
            combo.setValue(current);
        }
    }
    
    private void saveTeamworkAuthSelection(Object selected, String defaultUsername) {
        if (suppressTeamworkAuthSave) return;
        var globalSettingsManager = app.getGlobalSettingsManager();
        if (globalSettingsManager == null) {
            logger.warn("GlobalSettingsManager is null; cannot save teamwork auth selection.");
            return;
        }
        de.kortty.model.GlobalSettings gs = globalSettingsManager.getSettings();
        if (gs == null) {
            logger.warn("GlobalSettings is null; cannot save teamwork auth selection.");
            return;
        }
        if (selected == null) {
            gs.setTeamworkDefaultCredentialId(null);
            gs.setTeamworkDefaultSshKeyId(null);
            gs.setTeamworkDefaultUsername(null);
            gs.setTeamworkUseTemporaryKey(false);
        } else if (selected instanceof StoredCredential c) {
            gs.setTeamworkDefaultCredentialId(c.getId());
            gs.setTeamworkDefaultSshKeyId(null);
            gs.setTeamworkDefaultUsername(null);
            gs.setTeamworkUseTemporaryKey(false);
        } else if (selected instanceof SSHKey k) {
            gs.setTeamworkDefaultCredentialId(null);
            gs.setTeamworkDefaultSshKeyId(k.getId());
            gs.setTeamworkDefaultUsername(defaultUsername != null && !defaultUsername.isBlank() ? defaultUsername.trim() : null);
            gs.setTeamworkUseTemporaryKey(false);
        } else if (selected == TEAMWORK_AUTH_TEMPORARY_KEY) {
            gs.setTeamworkDefaultCredentialId(null);
            gs.setTeamworkDefaultSshKeyId(null);
            gs.setTeamworkDefaultUsername(defaultUsername != null && !defaultUsername.isBlank() ? defaultUsername.trim() : null);
            gs.setTeamworkUseTemporaryKey(true);
        }
        try {
            globalSettingsManager.save();
        } catch (Exception e) {
            logger.warn("Failed to save teamwork auth default", e);
        }
    }
    
    private void applyTeamworkAuthFromSettings(ComboBox<Object> combo, TextField usernameField) {
        suppressTeamworkAuthSave = true;
        try {
            var globalSettingsManager = app.getGlobalSettingsManager();
            if (globalSettingsManager == null) {
                logger.warn("GlobalSettingsManager is null; cannot apply teamwork auth from settings.");
                return;
            }
            de.kortty.model.GlobalSettings gs = globalSettingsManager.getSettings();
            if (gs == null) {
                logger.warn("GlobalSettings is null; cannot apply teamwork auth from settings.");
                return;
            }
            String credId = gs.getTeamworkDefaultCredentialId();
            String keyId = gs.getTeamworkDefaultSshKeyId();
            if (gs.getTeamworkUseTemporaryKey()) {
                combo.setValue(TEAMWORK_AUTH_TEMPORARY_KEY);
                usernameField.setText(gs.getTeamworkDefaultUsername() != null ? gs.getTeamworkDefaultUsername() : "");
            } else if (credId != null && credentialManager != null) {
                credentialManager.findCredentialById(credId).ifPresent(combo::setValue);
                usernameField.clear();
            } else if (keyId != null && app.getSSHKeyManager() != null) {
                app.getSSHKeyManager().findKeyById(keyId).ifPresent(k -> {
                    combo.setValue(k);
                    usernameField.setText(gs.getTeamworkDefaultUsername() != null ? gs.getTeamworkDefaultUsername() : "");
                });
            } else {
                combo.setValue(null);
                usernameField.clear();
            }
            Object val = combo.getValue();
            boolean needsUsername = val instanceof SSHKey || val == TEAMWORK_AUTH_TEMPORARY_KEY;
            usernameField.setVisible(needsUsername);
            usernameField.setManaged(needsUsername);
        } finally {
            suppressTeamworkAuthSave = false;
        }
    }
    
    private void showRestoreTeamworkDialog() {
        if (app.getTeamworkSyncService() == null) return;
        RestoreTeamworkDialog dialog = new RestoreTeamworkDialog(childOwner(), app);
        dialog.showAndWait().ifPresent(restored -> {
            if (restored != null) {
                app.getTeamworkSyncService().syncNow();
                refreshTeamworkConnections();
            }
        });
    }
    
    /**
     * Gets the currently selected single non-placeholder connection from the active tree.
     */
    private ServerConnection getSelectedConnection() {
        List<ServerConnection> selected = getActiveTreeView().getSelectedConnections();
        if (selected.size() == 1) {
            return selected.get(0);
        }
        return null;
    }
    
    private void addConnection() {
        // Determine target group from selection
        String targetGroup = "";
        List<GroupPath> selectedGroups = treeView.getSelectedGroups();
        if (!selectedGroups.isEmpty()) {
            targetGroup = selectedGroups.get(0).getPath();
        }
        
        final String finalTargetGroup = targetGroup;
        
        ConnectionEditDialog dialog = new ConnectionEditDialog(childOwner(), null, credentialManager, 
            app.getSSHKeyManager(), masterPassword);
        dialog.showAndWait().ifPresent(connection -> {
            if (finalTargetGroup != null && !finalTargetGroup.isEmpty()) {
                connection.setGroup(finalTargetGroup);
            }
            connections.add(connection);
            configManager.addConnection(connection);
            treeView.refreshPreservingFilter();
            saveConnections();
        });
    }
    
    private void editConnection(ServerConnection connection) {
        ServerConnection selected = connection != null ? connection : getSelectedConnection();
        // The editor writes the form into the object it is given, so only a local connection may reach it:
        // a teamwork connection is the live copy of a shared source that korTTY never writes back to.
        if (localEditIndex(connections, selected) < 0) {
            return;
        }
        ConnectionEditDialog dialog = new ConnectionEditDialog(childOwner(), selected, credentialManager, 
            app.getSSHKeyManager(), masterPassword);
        dialog.showAndWait().ifPresent(editedConnection -> {
            int index = localEditIndex(connections, selected);
            if (index < 0) {
                return;
            }
            connections.set(index, editedConnection);
            configManager.updateConnection(editedConnection);
            treeView.refreshPreservingFilter();
            saveConnections();
        });
    }

    /**
     * Where {@code connection} sits in the Connection Manager's own (local) list, or -1 when it cannot be
     * edited there: null, a placeholder, or any object that is not in that list, such as a teamwork
     * connection. Looked up by identity, as {@code indexOf} matches by id and a teamwork connection can carry
     * the id of a local one, which it would then replace.
     */
    static int localEditIndex(List<ServerConnection> localConnections, ServerConnection connection) {
        if (connection == null || connection.isPlaceholder()) {
            return -1;
        }
        for (int i = 0; i < localConnections.size(); i++) {
            if (localConnections.get(i) == connection) {
                return i;
            }
        }
        return -1;
    }
    
    private void deleteConnection() {
        ServerConnection selected = getSelectedConnection();
        if (selected != null) {
            deleteConnections(List.of(selected));
        }
    }
    
    private void deleteConnections(List<ServerConnection> connectionsToDelete) {
        if (connectionsToDelete.isEmpty()) {
            return;
        }
        
        // Filter out placeholders
        final List<ServerConnection> filteredConnections = connectionsToDelete.stream()
            .filter(conn -> !conn.isPlaceholder())
            .collect(Collectors.toList());
        
        if (filteredConnections.isEmpty()) {
            return;
        }
        
        List<ServerConnection> shared = filteredConnections.stream().filter(this::isSharedConnection).toList();
        if (!shared.isEmpty() && shared.size() < filteredConnections.size()) {
            // A mixed selection: hide the teamwork ones and delete the local ones, each with its own question.
            deleteConnections(shared);
            deleteConnections(filteredConnections.stream().filter(conn -> !isSharedConnection(conn)).toList());
            return;
        }
        if (!isLocalTabActive() || !shared.isEmpty()) {
            // Teamwork connections: move to recycle bin (hide locally), do not touch remote
            Alert confirmTeamwork = new Alert(Alert.AlertType.CONFIRMATION);
            confirmTeamwork.setTitle(I18n.get("connection.delete.title"));
            confirmTeamwork.setHeaderText(I18n.get("connection.delete.header", filteredConnections.size()));
            confirmTeamwork.setContentText(I18n.get("teamwork.delete.hideConfirm"));
            confirmTeamwork.showAndWait().ifPresent(result -> {
                if (result == ButtonType.OK && app.getTeamworkRecycleBinService() != null) {
                    for (ServerConnection conn : filteredConnections) {
                        app.getTeamworkRecycleBinService().addDeleted(conn);
                        teamworkConnections.remove(conn);
                    }
                    teamworkTreeView.refreshPreservingFilter();
                    treeView.refreshPreservingFilter();
                }
            });
            return;
        }
        
        Alert confirmLocal = new Alert(Alert.AlertType.CONFIRMATION);
        confirmLocal.setTitle(I18n.get("connection.delete.title"));
        confirmLocal.setHeaderText(I18n.get("connection.delete.header", filteredConnections.size()));
        confirmLocal.setContentText(I18n.get("connection.delete.content"));
        
        confirmLocal.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                for (ServerConnection conn : filteredConnections) {
                    connections.remove(conn);
                    configManager.removeConnection(conn);
                }
                treeView.refreshPreservingFilter();
                saveConnections();
            }
        });
    }
    
    private void duplicateConnection() {
        ServerConnection selected = getSelectedConnection();
        if (selected != null && !selected.isPlaceholder() && localEditIndex(connections, selected) >= 0) {
            ServerConnection copy = ServerConnection.copyForDuplicate(selected);
            copy.setName(selected.getName() + I18n.get("connManager.copy"));

            connections.add(copy);
            configManager.addConnection(copy);
            treeView.refreshPreservingFilter();
            saveConnections();
        }
    }

    private void assignTagToConnections(List<ServerConnection> targets) {
        List<ServerConnection> filtered = targets.stream()
            .filter(conn -> !conn.isPlaceholder())
            .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return;
        }
        String firstTag = filtered.get(0).getTag();
        boolean allSameTag = filtered.stream()
            .allMatch(conn -> java.util.Objects.equals(conn.getTag(), firstTag));
        TextInputDialog dialog = new TextInputDialog(allSameTag && firstTag != null ? firstTag : "");
        dialog.setTitle(I18n.get("connManager.assignTag.title"));
        dialog.setHeaderText(I18n.get("connManager.assignTag.header", filtered.size()));
        dialog.setContentText(I18n.get("connManager.assignTag.prompt"));
        dialog.initOwner(childOwner());
        dialog.initModality(Modality.WINDOW_MODAL);

        dialog.showAndWait().ifPresent(tag -> {
            String trimmed = tag != null ? tag.trim() : "";
            if (!trimmed.isEmpty()) {
                applyTag(filtered, trimmed);
            } else if (allSameTag && firstTag != null) {
                // The prompt was prefilled with the current tag; clearing it means "remove".
                applyTag(filtered, null);
            }
        });
    }

    private void removeTagFromConnections(List<ServerConnection> targets) {
        List<ServerConnection> filtered = targets.stream()
            .filter(conn -> !conn.isPlaceholder() && conn.getTag() != null)
            .collect(Collectors.toList());
        if (!filtered.isEmpty()) {
            applyTag(filtered, null);
        }
    }

    private void assignTagToGroup(GroupPath groupPath) {
        List<ServerConnection> targets = connectionsInGroup(groupPath);
        if (targets.isEmpty()) {
            showEmptyFolderAlert(groupPath);
            return;
        }
        assignTagToConnections(targets);
    }

    private void removeTagFromGroup(GroupPath groupPath) {
        List<ServerConnection> targets = connectionsInGroup(groupPath);
        if (targets.isEmpty()) {
            showEmptyFolderAlert(groupPath);
            return;
        }
        removeTagFromConnections(targets);
    }

    private void showEmptyFolderAlert(GroupPath groupPath) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.get("connManager.noConnections"));
        alert.setHeaderText(I18n.get("connManager.folderEmpty"));
        alert.setContentText(I18n.get("connection.emptyFolder", groupPath.getName()));
        alert.showAndWait();
    }

    private List<ServerConnection> connectionsInGroup(GroupPath groupPath) {
        return connections.stream()
            .filter(conn -> !conn.isPlaceholder())
            .filter(conn -> {
                if (conn.getGroup() == null) return false;
                GroupPath connPath = new GroupPath(conn.getGroup());
                return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
            })
            .collect(Collectors.toList());
    }

    private void applyTag(List<ServerConnection> targets, String tag) {
        for (ServerConnection conn : targets) {
            if (localEditIndex(connections, conn) < 0) {
                continue; // a teamwork connection's tag comes from its shared file
            }
            conn.setTag(tag);
            configManager.updateConnection(conn);
        }
        treeView.refreshPreservingFilter();
        saveConnections();
    }

    private void createNewGroup(GroupPath parentPath) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.get("connManager.newFolder"));
        dialog.setHeaderText(I18n.get("connManager.newFolderHeader") + 
            (parentPath.isRoot() ? "" : " in \"" + parentPath.getName() + "\""));
        dialog.setContentText(I18n.get("connManager.folderName"));
        dialog.initOwner(childOwner());
        dialog.initModality(Modality.WINDOW_MODAL);
        
        dialog.showAndWait().ifPresent(name -> {
            if (name != null && !name.trim().isEmpty()) {
                GroupPath newGroupPath = parentPath.isRoot() ? new GroupPath(name) : parentPath.append(name);
                
                // Create placeholder connection for empty group
                ServerConnection placeholder = new ServerConnection();
                placeholder.setName("(Ordner: " + newGroupPath.getName() + ")");
                placeholder.setHost("placeholder");
                placeholder.setPort(22);
                placeholder.setUsername("");
                placeholder.setGroup(newGroupPath.getPath());
                
                connections.add(placeholder);
                configManager.addConnection(placeholder);
                treeView.refreshPreservingFilter();
                saveConnections();
            }
        });
    }
    
    private boolean isGroupHostKeyCheckDisabled(GroupPath groupPath) {
        try {
            var gsm = app.getGlobalSettingsManager();
            var settings = gsm != null ? gsm.getSettings() : null;
            return settings != null && settings.isHostKeyCheckDisabledForGroup(groupPath.getPath());
        } catch (Exception e) {
            return false;
        }
    }

    private void toggleGroupHostKeyCheck(GroupPath groupPath, boolean disabled) {
        try {
            var gsm = app.getGlobalSettingsManager();
            var settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null) {
                return;
            }
            if (disabled) {
                Alert confirm = new Alert(Alert.AlertType.WARNING,
                    I18n.get("connManager.group.disableHostKeyCheck.confirm", groupPath.getName()),
                    ButtonType.OK, ButtonType.CANCEL);
                DialogThemeHelper.applyTheme(confirm);
                confirm.setHeaderText(null);
                confirm.initOwner(childOwner());
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    return; // leave it enabled; the CheckMenuItem re-renders correct state on next open
                }
            }
            settings.setHostKeyCheckDisabledForGroup(groupPath.getPath(), disabled);
            gsm.save();
        } catch (Exception e) {
            logger.error("Could not toggle host-key verification for group {}", groupPath.getPath(), e);
        }
    }

    /**
     * Gives the folder {@code groupPath} the isolation {@code level}, or removes its own level for null, and
     * saves the global settings; a failed save puts the previous levels back. Sessions that are open keep
     * the isolation they started with; the next one a connection in the folder opens gets the new level.
     */
    private void setGroupIsolation(GroupPath groupPath, de.kortty.isolation.IsolationLevel level) {
        String key = de.kortty.isolation.ConnectionGroupIsolation.key(groupPath.getPath());
        GlobalSettings settings = globalSettings();
        if (key == null || settings == null) {
            return;
        }
        Map<String, de.kortty.isolation.IsolationLevel> levels = settings.getConnectionGroupIsolation();
        if (level != null) {
            levels.put(key, level);
        } else {
            levels.remove(key);
        }
        storeGroupIsolation(levels);
    }

    /** Makes {@code levels} the folders' isolation levels and saves; a failed save restores the old ones. */
    private boolean storeGroupIsolation(Map<String, de.kortty.isolation.IsolationLevel> levels) {
        var gsm = app.getGlobalSettingsManager();
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null) {
            return false;
        }
        Map<String, de.kortty.isolation.IsolationLevel> previous = settings.getConnectionGroupIsolation();
        if (previous.equals(de.kortty.isolation.ConnectionGroupIsolation.copyOf(levels))) {
            return true;
        }
        settings.setConnectionGroupIsolation(levels);
        try {
            gsm.save();
            return true;
        } catch (Exception e) {
            settings.setConnectionGroupIsolation(previous);
            logger.error("Could not save the folder isolation levels", e);
            return false;
        }
    }

    /** The tab color the folder {@code groupPath} has of its own, or null. */
    private String groupColorOf(GroupPath groupPath) {
        GlobalSettings settings = globalSettings();
        return settings != null ? settings.getConnectionGroupColor(groupPath.getPath()) : null;
    }

    private GlobalSettings globalSettings() {
        var gsm = app.getGlobalSettingsManager();
        return gsm != null ? gsm.getSettings() : null;
    }

    /**
     * Lets the user give the folder {@code groupPath} a tab color of its own or remove it, then
     * stores the colors and recolors the open tabs in every window.
     */
    private void editGroupColor(GroupPath groupPath) {
        GlobalSettings settings = globalSettings();
        if (settings == null) {
            return;
        }
        Map<String, String> colors = settings.getConnectionGroupColors();
        ConnectionGroupColorDialog dialog = new ConnectionGroupColorDialog(groupPath, colors);
        dialog.initOwner(childOwner());
        dialog.showAndWait().ifPresent(choice -> {
            String key = ConnectionGroupColors.key(groupPath.getPath());
            if (key == null) {
                return;
            }
            if (choice.color() != null) {
                colors.put(key, choice.color());
            } else {
                colors.remove(key);
            }
            if (storeGroupColors(colors)) {
                treeView.refreshPreservingFilter();
                MainWindow.refreshConnectionColorsInAllWindows();
            }
        });
    }

    /**
     * Makes {@code colors} the tab colors of the folders and saves the global settings; a failed save
     * puts the previous colors back and says so. Unchanged colors are not saved again.
     *
     * @return whether the colors are now {@code colors}
     */
    private boolean storeGroupColors(Map<String, String> colors) {
        var gsm = app.getGlobalSettingsManager();
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null) {
            return false;
        }
        Map<String, String> previous = settings.getConnectionGroupColors();
        if (previous.equals(ConnectionGroupColors.copyOf(colors))) {
            return true;
        }
        settings.setConnectionGroupColors(colors);
        try {
            gsm.save();
            return true;
        } catch (Exception e) {
            settings.setConnectionGroupColors(previous);
            logger.error("Could not save the folder tab colors", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(alert);
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("error.saveFailed"));
            alert.setContentText(e.getMessage());
            alert.showAndWait();
            return false;
        }
    }

    /**
     * The folders whose host-key verification is relaxed once {@code change} is applied to the stored
     * ones, or null without global settings.
     */
    private List<String> hostKeyCheckExemptionsAfter(UnaryOperator<List<String>> change) {
        var gsm = app.getGlobalSettingsManager();
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        return settings != null ? change.apply(new ArrayList<>(settings.getHostKeyCheckDisabledGroups())) : null;
    }

    /**
     * Makes {@code exempt} the folders whose host-key verification is relaxed and saves the global
     * settings; unchanged exemptions are not saved again. A failed save keeps them in memory, since
     * after a rename or delete they relax no connection that was verified before, and says so.
     */
    private void storeHostKeyCheckExemptions(List<String> exempt) {
        var gsm = app.getGlobalSettingsManager();
        GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
        if (settings == null || exempt == null || exempt.equals(settings.getHostKeyCheckDisabledGroups())) {
            return;
        }
        settings.setHostKeyCheckDisabledGroups(new ArrayList<>(exempt));
        try {
            gsm.save();
        } catch (Exception e) {
            logger.error("Could not save the folders without host-key verification", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(alert);
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("error.saveFailed"));
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    private void renameGroup(GroupPath oldPath) {
        TextInputDialog dialog = new TextInputDialog(oldPath.getName());
        dialog.setTitle(I18n.get("connManager.renameFolder"));
        dialog.setHeaderText(I18n.get("connManager.renameFolderHeader", oldPath.getName()));
        dialog.setContentText(I18n.get("connManager.newName"));
        dialog.initOwner(childOwner());
        dialog.initModality(Modality.WINDOW_MODAL);
        
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(oldPath.getName())) {
                GroupPath parentPath = oldPath.getParent();
                GroupPath newPath = parentPath == null || parentPath.isRoot() ? 
                    new GroupPath(newName) : parentPath.append(newName);
                // Host-key exemptions move with the folders; worked out while the groups are still the old ones.
                List<String> exemptionsAfter = hostKeyCheckExemptionsAfter(exempt -> HostKeyCheckGroupExemptions.renamed(
                    exempt, oldPath.getPath(), newPath.getPath(), connections.stream().map(ServerConnection::getGroup).toList()));
                
                // Update all connections in this group and sub-groups
                for (ServerConnection conn : connections) {
                    if (conn.getGroup() != null) {
                        GroupPath connPath = new GroupPath(conn.getGroup());
                        if (connPath.equals(oldPath) || connPath.isChildOf(oldPath)) {
                            // Replace old path segment with new one
                            String oldPathStr = oldPath.getPath();
                            String newPathStr = newPath.getPath();
                            String connGroup = conn.getGroup();
                            
                            if (connGroup.equals(oldPathStr)) {
                                conn.setGroup(newPathStr);
                            } else if (connGroup.startsWith(oldPathStr + GroupPath.SEPARATOR)) {
                                conn.setGroup(newPathStr + connGroup.substring(oldPathStr.length()));
                            }
                            
                            configManager.updateConnection(conn);
                        }
                    }
                }
                // The folder's tab color, and those of its subfolders, move along with the name.
                GlobalSettings settings = globalSettings();
                if (settings != null) {
                    storeGroupColors(ConnectionGroupColors.renamed(
                        settings.getConnectionGroupColors(), oldPath.getPath(), newPath.getPath()));
                    MainWindow.renameIconToolbarGroup(oldPath.getPath(), newPath.getPath(), GroupPath.SEPARATOR);
                    // The isolation follows the folder, never its old name.
                    storeGroupIsolation(de.kortty.isolation.ConnectionGroupIsolation.renamed(
                        settings.getConnectionGroupIsolation(), oldPath.getPath(), newPath.getPath()));
                }
                
                treeView.refreshPreservingFilter();
                saveConnections();
                storeHostKeyCheckExemptions(exemptionsAfter);
                if (scope.kind() == ScopeKind.GROUP && (scope.value().equals(oldPath.getPath())
                        || new GroupPath(scope.value()).isChildOf(oldPath))) {
                    setScope(new Scope(ScopeKind.GROUP, newPath.getPath() + scope.value().substring(oldPath.getPath().length())));
                }
            }
        });
    }
    
    private void deleteGroup(GroupPath groupPath) {
        // Count connections in this group (and sub-groups)
        long count = connections.stream()
            .filter(conn -> {
                if (conn.getGroup() == null) return false;
                GroupPath connPath = new GroupPath(conn.getGroup());
                return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
            })
            .filter(conn -> !conn.isPlaceholder())
            .count();
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("connection.deleteFolder.title"));
        confirm.setHeaderText(I18n.get("connection.deleteFolder.header", groupPath.getName()));
        confirm.setContentText(I18n.get("connection.deleteFolder.content", count));
        
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                List<ServerConnection> toRemove = connections.stream()
                    .filter(conn -> {
                        if (conn.getGroup() == null) return false;
                        GroupPath connPath = new GroupPath(conn.getGroup());
                        return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
                    })
                    .collect(Collectors.toList());
                
                for (ServerConnection conn : toRemove) {
                    connections.remove(conn);
                    configManager.removeConnection(conn);
                }
                // A new folder of the same name starts without the deleted one's tab color.
                GlobalSettings settings = globalSettings();
                if (settings != null) {
                    storeGroupColors(ConnectionGroupColors.deleted(
                        settings.getConnectionGroupColors(), groupPath.getPath()));
                    // A new folder of the same name starts without the deleted one's isolation.
                    storeGroupIsolation(de.kortty.isolation.ConnectionGroupIsolation.deleted(
                        settings.getConnectionGroupIsolation(), groupPath.getPath()));
                }
                
                treeView.refreshPreservingFilter();
                saveConnections();
                // A new folder of the same name starts with host-key verification on.
                storeHostKeyCheckExemptions(hostKeyCheckExemptionsAfter(
                    exempt -> HostKeyCheckGroupExemptions.deleted(exempt, groupPath.getPath())));
                if (scope.kind() == ScopeKind.GROUP && (scope.value().equals(groupPath.getPath())
                        || new GroupPath(scope.value()).isChildOf(groupPath))) {
                    setScope(Scope.ALL);
                }
            }
        });
    }
    
    /**
     * The window this manager's own dialogs belong to: the manager itself, not the main window. A
     * child dialog owned by the main window returns the focus there when it closes — to a window
     * the manager still blocks as a modal, which macOS answers with its error beep on every Save
     * or Cancel of the connection editor.
     */
    private Stage childOwner() {
        Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        return window instanceof Stage stage && stage.isShowing() ? stage : owner;
    }

    private void saveConnections() {
        try {
            // saveOrThrow, not save: a refused or failed write must reach the error alert below.
            configManager.saveOrThrow(app.getMasterPasswordManager().getDerivedKey());
            logger.info("Connections saved successfully");
            MainWindow.refreshIconToolbars();
            if (onConnectionsSavedCallback != null) {
                javafx.application.Platform.runLater(onConnectionsSavedCallback);
            }
        } catch (Exception e) {
            logger.error("Failed to save connections", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("error.saveFailed"));
            alert.setContentText(I18n.get("connManager.saveFailed") + e.getMessage());
            alert.showAndWait();
        }
    }
    
    private void exportConnections() {
        List<ServerConnection> selectedConnections = getActiveTreeView().getSelectedConnections();
        if (!selectedConnections.isEmpty()) {
            exportConnections(selectedConnections);
        }
    }
    
    private void exportConnections(List<ServerConnection> connectionsToExport) {
        if (connectionsToExport.isEmpty()) {
            return;
        }
        
        // Filter out placeholders
        final List<ServerConnection> finalConnectionsToExport = connectionsToExport.stream()
            .filter(conn -> !conn.isPlaceholder())
            .collect(Collectors.toList());
        
        if (finalConnectionsToExport.isEmpty()) {
            return;
        }
        
        ConnectionExportDialog dialog = new ConnectionExportDialog(childOwner(), finalConnectionsToExport,
            isLocalTabActive() ? connections : teamworkConnections);
        dialog.showAndWait().ifPresent(result -> {
            try {
                exportConnectionsToFile(result);

                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle(I18n.get("connManager.exportSuccess"));
                success.setHeaderText(I18n.get("connManager.exportedCount", result.connections.size()));
                success.setContentText(I18n.get("connManager.file") + ": " + result.exportFile.getAbsolutePath());
                success.showAndWait();
            } catch (Exception e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(I18n.get("error.exportFailed"));
                error.setHeaderText(I18n.get("connManager.exportError"));
                error.setContentText(e.getMessage());
                error.showAndWait();
            }
        });
    }
    
    private void exportGroup(GroupPath groupPath) {
        // Collect all non-placeholder connections in this group and sub-groups
        List<ServerConnection> connectionsInGroup = connections.stream()
            .filter(conn -> {
                if (conn.getGroup() == null || conn.isPlaceholder()) return false;
                GroupPath connPath = new GroupPath(conn.getGroup());
                return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
            })
            .collect(Collectors.toList());
        
        if (!connectionsInGroup.isEmpty()) {
            exportConnections(connectionsInGroup);
        } else {
            showEmptyFolderAlert(groupPath);
        }
    }
    
    private void exportConnectionsToFile(ConnectionExportDialog.ExportResult result) throws Exception {
        // Create copies of connections and filter based on options
        List<ServerConnection> exportList = new ArrayList<>();

        for (ServerConnection conn : result.connections) {
            exportList.add(ServerConnection.copyForExport(conn,
                result.includeUsername, result.includePassword,
                result.includeTunnels, result.includeJumpServer));
        }
        
        // Use the selected exporter
        if (result.exporter != null) {
            result.exporter.exportConnections(exportList, result.exportFile.toPath());
        } else {
            // Fallback to KorTTY XML format
            exportToKorTTYFormat(exportList, result.exportFile);
        }
    }
    
    /**
     * Exports connections to KorTTY XML format (fallback/default).
     */
    private void exportToKorTTYFormat(List<ServerConnection> exportList, java.io.File exportFile) throws Exception {
        SecretKey persistenceKey = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getDerivedKey()
            : null;
        de.kortty.persistence.XMLConnectionRepository.writeConnections(exportList, exportFile.toPath(), persistenceKey);
    }
    
    private void importConnections() {
        ConnectionImportDialog dialog = new ConnectionImportDialog(childOwner(), credentialManager, 
            app.getSSHKeyManager(), configManager);
        dialog.showAndWait().ifPresent(result -> {
            try {
                List<ServerConnection> importedConnections = importConnectionsFromFile(result);
                
                // Add to connections list and config
                int successCount = 0;
                for (ServerConnection conn : importedConnections) {
                    connections.add(conn);
                    configManager.addConnection(conn);
                    successCount++;
                }
                
                // Refresh tree and save
                treeView.refreshPreservingFilter();
                saveConnections();
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle(I18n.get("info.importSuccessful", successCount, ""));
                success.setHeaderText(I18n.get("connManager.importedCount", successCount));
                success.setContentText(I18n.get("info.importSuccessful", successCount, ""));
                success.showAndWait();
            } catch (Exception e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(I18n.get("error.importFailed"));
                error.setHeaderText(I18n.get("connManager.importError"));
                error.setContentText(e.getMessage());
                error.showAndWait();
            }
        });
    }
    
    private List<ServerConnection> importConnectionsFromFile(ConnectionImportDialog.ImportResult result) throws Exception {
        // Determine file type and extract/decrypt if needed
        java.io.File actualXmlFile = result.importFile;
        java.nio.file.Path tempFile = null;
        boolean needsCleanup = false;
        
        try {
            String fileName = result.importFile.getName().toLowerCase();
            
            // Handle GPG-encrypted files
            if (fileName.endsWith(".gpg")) {
                // Decrypt GPG file first
                tempFile = java.nio.file.Files.createTempFile("kortty-import-", ".zip");
                needsCleanup = true;
                
                // Decrypt GPG file
                ProcessBuilder pb = new ProcessBuilder(
                    "gpg",
                    "--batch",
                    "--yes",
                    "--no-tty",
                    "--quiet",
                    "--decrypt",
                    "--output", tempFile.toString(),
                    result.importFile.getAbsolutePath()
                );
                
                pb.redirectErrorStream(true);
                String osName = System.getProperty("os.name").toLowerCase();
                java.io.File nullFile = new java.io.File(osName.contains("win") ? "NUL" : "/dev/null");
                pb.redirectInput(ProcessBuilder.Redirect.from(nullFile));
                
                Process process = pb.start();
                
                StringBuilder output = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new Exception(I18n.get("error.gpgEncryptionFailed", exitCode, output.toString(), "key"));
                }
                
                // After GPG decryption, we should have a ZIP file
                actualXmlFile = tempFile.toFile();
            }
            
            // Handle ZIP files (password-protected or not)
            if (fileName.endsWith(".zip") || (fileName.endsWith(".gpg") && tempFile != null)) {
                java.nio.file.Path zipTempFile = tempFile != null ? tempFile : result.importFile.toPath();
                
                // Try to extract ZIP
                net.lingala.zip4j.ZipFile zipFile = new net.lingala.zip4j.ZipFile(zipTempFile.toFile());
                
                // Check if ZIP is password protected
                if (zipFile.isEncrypted()) {
                    // Ask for password using custom dialog with password field and stored credentials
                    Dialog<String> passwordDialog = new Dialog<>();
                    passwordDialog.setTitle(I18n.get("dialog.passwordRequired"));
                    passwordDialog.setHeaderText(I18n.get("connImport.zipPasswordProtected"));
                    passwordDialog.initModality(Modality.WINDOW_MODAL);
                    passwordDialog.initOwner(childOwner());
                    
                    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
                    grid.setHgap(10);
                    grid.setVgap(10);
                    grid.setPadding(new javafx.geometry.Insets(20));
                    
                    Label passwordLabel = new Label(I18n.get("common.password") + ":");
                    PasswordField passwordField = new PasswordField();
                    passwordField.setPromptText(I18n.get("dialog.enterPassword"));
                    passwordField.setPrefWidth(300);
                    
                    Label storedLabel = new Label(I18n.get("connManager.orSelect"));
                    ComboBox<StoredCredential> storedCredentialCombo = new ComboBox<>();
                    storedCredentialCombo.setPromptText(I18n.get("connManager.selectStoredPassword"));
                    storedCredentialCombo.setPrefWidth(300);
                    
                    storedCredentialCombo.setCellFactory(lv -> new ListCell<StoredCredential>() {
                        @Override
                        protected void updateItem(StoredCredential item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
                        }
                    });
                    storedCredentialCombo.setButtonCell(new ListCell<StoredCredential>() {
                        @Override
                        protected void updateItem(StoredCredential item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
                        }
                    });
                    
                    // Load stored credentials
                    if (credentialManager != null) {
                        storedCredentialCombo.getItems().addAll(credentialManager.getAllCredentials());
                    }
                    
                    // When a stored credential is selected, fill the password field. An external
                    // password command runs in the background; only the still-selected
                    // credential's result is applied.
                    CredentialPasswordResolver passwordResolver = new CredentialPasswordResolver();
                    passwordDialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> passwordResolver.cancel());
                    String enterPasswordPrompt = passwordField.getPromptText();
                    storedCredentialCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
                        if (newVal == null || credentialManager == null || masterPassword == null) {
                            passwordResolver.cancel();
                            passwordField.setDisable(false);
                            passwordField.setPromptText(enterPasswordPrompt);
                            return;
                        }
                        passwordResolver.resolve(credentialManager, newVal, masterPassword,
                            new CredentialPasswordResolver.Listener() {
                                @Override
                                public void started(StoredCredential credential) {
                                    passwordField.clear();
                                    passwordField.setDisable(true);
                                    passwordField.setPromptText(I18n.get("credential.externalCommand.running"));
                                }

                                @Override
                                public void resolved(StoredCredential credential, String password) {
                                    passwordField.setDisable(false);
                                    passwordField.setPromptText(enterPasswordPrompt);
                                    if (password != null && !password.isEmpty()) {
                                        passwordField.setText(password);
                                    }
                                }

                                @Override
                                public void failed(StoredCredential credential, Exception e) {
                                    passwordField.setDisable(false);
                                    passwordField.setPromptText(enterPasswordPrompt);
                                    logger.warn("Failed to resolve password for credential: {}", credential.getName(), e);
                                    Alert alert = new Alert(Alert.AlertType.WARNING);
                                    alert.setTitle(I18n.get("error.title"));
                                    alert.setHeaderText(I18n.get("credential.passwordDecryptFailed"));
                                    alert.setContentText(I18n.get("credential.passwordDecryptFailedMessage", e.getMessage()));
                                    alert.showAndWait();
                                    passwordField.clear();
                                }
                            });
                    });
                    
                    grid.add(passwordLabel, 0, 0);
                    grid.add(passwordField, 1, 0);
                    grid.add(storedLabel, 0, 1);
                    grid.add(storedCredentialCombo, 1, 1);
                    
                    javafx.scene.layout.GridPane.setHgrow(passwordField, javafx.scene.layout.Priority.ALWAYS);
                    javafx.scene.layout.GridPane.setHgrow(storedCredentialCombo, javafx.scene.layout.Priority.ALWAYS);
                    
                    passwordDialog.getDialogPane().setContent(grid);
                    
                    ButtonType okButtonType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
                    passwordDialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);
                    
                    Button okButton = (Button) passwordDialog.getDialogPane().lookupButton(okButtonType);
                    okButton.setDisable(true);
                    
                    // Enable OK button only when password is entered
                    passwordField.textProperty().addListener((obs, oldVal, newVal) -> {
                        okButton.setDisable(newVal == null || newVal.trim().isEmpty());
                    });
                    
                    passwordDialog.setResultConverter(buttonType -> {
                        if (buttonType == okButtonType) {
                            return passwordField.getText();
                        }
                        return null;
                    });
                    
                    // Focus password field when dialog is shown
                    passwordField.requestFocus();
                    
                    Optional<String> passwordOpt = passwordDialog.showAndWait();
                    if (!passwordOpt.isPresent() || passwordOpt.get().trim().isEmpty()) {
                        throw new Exception("ZIP decryption cancelled: No password entered");
                    }
                    
                    zipFile.setPassword(passwordOpt.get().toCharArray());
                }
                
                // Extract to temp directory
                java.nio.file.Path extractDir = java.nio.file.Files.createTempDirectory("kortty-import-extract-");
                needsCleanup = true;
                
                zipFile.extractAll(extractDir.toString());
                
                // Find XML file in extracted directory
                List<java.nio.file.Path> xmlFiles = java.nio.file.Files.walk(extractDir)
                    .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                    .collect(java.util.stream.Collectors.toList());
                
                if (xmlFiles.isEmpty()) {
                    throw new Exception(I18n.get("connImport.noXmlInZip"));
                }
                
                actualXmlFile = xmlFiles.get(0).toFile();
                
                // Clean up temp file if it was created for GPG
                if (tempFile != null && !tempFile.equals(zipTempFile)) {
                    java.nio.file.Files.deleteIfExists(tempFile);
                }
                tempFile = extractDir; // Mark extractDir for cleanup
            }
            
            // Read XML file
            SecretKey persistenceKey = app.getMasterPasswordManager() != null
                ? app.getMasterPasswordManager().getDerivedKey()
                : null;
            List<ServerConnection> persistedConnections =
                de.kortty.persistence.XMLConnectionRepository.readConnections(actualXmlFile.toPath(), persistenceKey);
        
            List<ServerConnection> importList = new ArrayList<>();
        
            for (ServerConnection conn : persistedConnections) {
                // Group filtering
                if (result.filterGroups && !result.selectedGroups.isEmpty()) {
                    String connGroup = conn.getGroup();
                    boolean isNoGroup = connGroup == null || connGroup.trim().isEmpty();
                    
                    // Check if connection matches selected groups
                    boolean matchesFilter = false;
                    for (String selectedGroup : result.selectedGroups) {
                        if (selectedGroup.equals("(keine Gruppe)") && isNoGroup) {
                            matchesFilter = true;
                            break;
                        } else if (!isNoGroup && selectedGroup.equals(connGroup)) {
                            matchesFilter = true;
                            break;
                        }
                    }
                    
                    if (!matchesFilter) {
                        continue;  // Skip this connection
                    }
                }
                
                // Everything the export wrote, including the protocol, a local shell's command and start
                // directory, and the paste protection (shown under Terminal behavior in the editor);
                // username, password, tunnels and jump server follow the checkboxes.
                // The key passphrase is never imported: it is only ever stored encrypted (set in the
                // edit dialog or key manager).
                ServerConnection imported = ServerConnection.copyForImport(conn,
                    result.importUsername, result.importPassword,
                    result.importTunnels, result.importJumpServer);
                
                // SSH Key (replaced)
                if (result.replaceSSHKey && result.replacementSSHKey != null) {
                    // Replace with selected SSH key
                    imported.setSshKeyId(result.replacementSSHKey.getId());
                    imported.setPrivateKeyPath(app.getSSHKeyManager() != null ? 
                        app.getSSHKeyManager().getEffectiveKeyPath(result.replacementSSHKey) : 
                        result.replacementSSHKey.getKeyPath());
                }
                
                // Group assignment (target group overrides original group)
                if (result.assignToGroup && result.targetGroup != null && !result.targetGroup.trim().isEmpty()) {
                    imported.setGroup(result.targetGroup);
                }
                imported.setTabColor(de.kortty.core.ConnectionColorSupport.normalizeHex(conn.getTabColor()));
                
                // Password (replaced)
                if (result.replaceCredentials && result.replacementCredential != null) {
                    // Replace with selected credential
                    imported.setUsername(result.replacementCredential.getUsername());
                    imported.setCredentialId(result.replacementCredential.getId());
                    imported.setEncryptedPassword(null);  // Use credential instead
                }
                
                importList.add(imported);
            }
            
            return importList;
        } finally {
            // Clean up temporary files
            if (needsCleanup && tempFile != null) {
                try {
                    if (java.nio.file.Files.isDirectory(tempFile)) {
                        // Recursively delete directory
                        java.nio.file.Files.walk(tempFile)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    java.nio.file.Files.delete(p);
                                } catch (Exception e) {
                                    // Ignore cleanup errors
                                }
                            });
                    } else {
                        java.nio.file.Files.deleteIfExists(tempFile);
                    }
                } catch (Exception e) {
                    // Ignore cleanup errors
                    logger.warn("Failed to cleanup temp file: {}", tempFile, e);
                }
            }
        }
    }
    
}
