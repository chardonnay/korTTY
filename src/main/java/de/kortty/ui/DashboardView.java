package de.kortty.ui;

import de.kortty.core.SSHSession;
import de.kortty.core.SessionManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Dashboard view showing a tree of all windows and tabs.
 */
public class DashboardView extends VBox {
    
    private final SessionManager sessionManager;
    private final Consumer<String> sessionSelector;
    private final TreeView<DashboardItem> treeView;
    
    public DashboardView(SessionManager sessionManager, Consumer<String> sessionSelector) {
        this.sessionManager = sessionManager;
        this.sessionSelector = sessionSelector;
        
        setPadding(new Insets(5));
        setSpacing(5);
        setStyle("-fx-background-color: #2d2d2d;");
        
        Label titleLabel = new Label("Dashboard");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #cccccc;");
        
        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.setStyle("-fx-background-color: #2d2d2d;");
        
        // Custom cell factory
        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(DashboardItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText(item.displayName());
                    setStyle("-fx-text-fill: " + (item.connected() ? "#00ff00" : "#ff6666") + ";");
                }
            }
        });
        
        // Double-click to focus session
        treeView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<DashboardItem> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue().sessionId() != null) {
                    sessionSelector.accept(selected.getValue().sessionId());
                }
            }
        });
        
        VBox.setVgrow(treeView, javafx.scene.layout.Priority.ALWAYS);
        
        getChildren().addAll(titleLabel, treeView);
        
        refresh();
    }
    
    /**
     * Refreshes the dashboard tree.
     */
    public void refresh() {
        TreeItem<DashboardItem> root = new TreeItem<>(new DashboardItem("Projekt", null, true));
        
        // Group sessions by window (for now, all in one window)
        TreeItem<DashboardItem> windowItem = new TreeItem<>(
                new DashboardItem("Hauptfenster", null, true)
        );
        windowItem.setExpanded(true);
        
        for (SSHSession session : sessionManager.getAllSessions()) {
            String displayName = session.generateTabTitle();
            boolean connected = session.isConnected();
            
            TreeItem<DashboardItem> sessionItem = new TreeItem<>(
                    new DashboardItem(displayName, session.getSessionId(), connected)
            );
            windowItem.getChildren().add(sessionItem);
        }
        
        if (!windowItem.getChildren().isEmpty()) {
            root.getChildren().add(windowItem);
        }
        
        root.setExpanded(true);
        treeView.setRoot(root);
        
        // Update window title
        windowItem.setValue(new DashboardItem(
                "Hauptfenster (" + windowItem.getChildren().size() + " Verbindungen)",
                null,
                true
        ));
    }
    
    /**
     * Record for dashboard tree items.
     */
    private record DashboardItem(String displayName, String sessionId, boolean connected) {}
}
