package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.model.SessionState;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tab for viewing images with zoom and pan capabilities.
 * Supports PNG, JPG, GIF, WEBP formats.
 */
public class ImageViewerTab extends Tab {
    
    private static final Logger logger = LoggerFactory.getLogger(ImageViewerTab.class);
    
    private final ImageView imageView;
    private final Label statusLabel;
    private final SFTPSession sftpSession;
    private final String remotePath;
    private final boolean isRemoteFile;
    private Path localPath;
    private byte[] imageData;
    
    private double zoomLevel = 1.0;
    private static final double ZOOM_MIN = 0.1;
    private static final double ZOOM_MAX = 10.0;
    private static final double ZOOM_STEP = 0.1;
    
    /**
     * Constructor for remote image viewing.
     */
    public ImageViewerTab(String filename, String remotePath, SFTPSession sftpSession, byte[] imageData) {
        this.remotePath = remotePath;
        this.sftpSession = sftpSession;
        this.isRemoteFile = true;
        this.imageData = imageData;
        
        setText(filename + " (Remote)");
        setClosable(true);
        
        // Create image view
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        
        // Load image
        try {
            Image image = new Image(new ByteArrayInputStream(imageData));
            imageView.setImage(image);
        } catch (Exception e) {
            logger.error("Failed to load remote image", e);
            showError(I18n.get("error.title"), I18n.get("imageViewer.error.load", e.getMessage()));
        }
        
        // Status bar
        statusLabel = new Label(getImageInfo());
        statusLabel.setStyle("-fx-padding: 5px;");
        
        // Create UI
        BorderPane content = createContent();
        setContent(content);
        
        logger.info("Opened remote image for viewing: {}", remotePath);
    }
    
    /**
     * Constructor for local image viewing.
     */
    public ImageViewerTab(Path localPath) throws Exception {
        this.localPath = localPath;
        this.remotePath = null;
        this.sftpSession = null;
        this.isRemoteFile = false;
        
        setText(localPath.getFileName().toString());
        setClosable(true);
        
        // Create image view
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        
        // Load image
        try {
            this.imageData = Files.readAllBytes(localPath);
            Image image = new Image(new ByteArrayInputStream(imageData));
            imageView.setImage(image);
        } catch (Exception e) {
            logger.error("Failed to load local image", e);
            throw new Exception(I18n.get("imageViewer.error.load", e.getMessage()));
        }
        
        // Status bar
        statusLabel = new Label(getImageInfo());
        statusLabel.setStyle("-fx-padding: 5px;");
        
        // Create UI
        BorderPane content = createContent();
        setContent(content);
        
        logger.info("Opened local image for viewing: {}", localPath);
    }
    
    private BorderPane createContent() {
        BorderPane root = new BorderPane();
        
        // Toolbar
        ToolBar toolBar = createToolBar();
        root.setTop(toolBar);
        
        // Image container with scroll pane
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: #333333;");
        
        StackPane imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: #333333;");
        imageContainer.setAlignment(Pos.CENTER);
        
        scrollPane.setContent(imageContainer);
        root.setCenter(scrollPane);
        
        // Mouse wheel zoom
        scrollPane.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.isControlDown()) {
                if (event.getDeltaY() > 0) {
                    zoomIn();
                } else {
                    zoomOut();
                }
                event.consume();
            }
        });
        
        // Status bar
        HBox statusBar = new HBox(statusLabel);
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        root.setBottom(statusBar);
        
        // Initial fit to window
        fitToWindow();
        
        return root;
    }
    
    private ToolBar createToolBar() {
        Button zoomInBtn = new Button(I18n.get("imageViewer.zoomIn"));
        zoomInBtn.setOnAction(e -> zoomIn());
        
        Button zoomOutBtn = new Button(I18n.get("imageViewer.zoomOut"));
        zoomOutBtn.setOnAction(e -> zoomOut());
        
        Button zoomResetBtn = new Button(I18n.get("imageViewer.zoomReset"));
        zoomResetBtn.setOnAction(e -> zoomReset());
        
        Button fitToWindowBtn = new Button(I18n.get("imageViewer.fitToWindow"));
        fitToWindowBtn.setOnAction(e -> fitToWindow());
        
        Button actualSizeBtn = new Button(I18n.get("imageViewer.actualSize"));
        actualSizeBtn.setOnAction(e -> actualSize());
        
        Button saveAsBtn = new Button(I18n.get("imageViewer.saveAs"));
        saveAsBtn.setOnAction(e -> saveAs());
        
        return new ToolBar(zoomInBtn, zoomOutBtn, zoomResetBtn, new Separator(), 
                          fitToWindowBtn, actualSizeBtn, new Separator(), saveAsBtn);
    }
    
    private void zoomIn() {
        if (zoomLevel < ZOOM_MAX) {
            zoomLevel += ZOOM_STEP;
            applyZoom();
        }
    }
    
    private void zoomOut() {
        if (zoomLevel > ZOOM_MIN) {
            zoomLevel -= ZOOM_STEP;
            applyZoom();
        }
    }
    
    private void zoomReset() {
        zoomLevel = 1.0;
        applyZoom();
    }
    
    private void fitToWindow() {
        Image image = imageView.getImage();
        if (image == null) return;
        
        BorderPane root = (BorderPane) getContent();
        ScrollPane scrollPane = (ScrollPane) root.getCenter();
        
        double availableWidth = scrollPane.getWidth() - 20;
        double availableHeight = scrollPane.getHeight() - 20;
        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        
        double scaleX = availableWidth / imageWidth;
        double scaleY = availableHeight / imageHeight;
        
        zoomLevel = Math.min(scaleX, scaleY);
        if (zoomLevel > ZOOM_MAX) zoomLevel = ZOOM_MAX;
        if (zoomLevel < ZOOM_MIN) zoomLevel = ZOOM_MIN;
        
        applyZoom();
    }
    
    private void actualSize() {
        zoomLevel = 1.0;
        applyZoom();
    }
    
    private void applyZoom() {
        Image image = imageView.getImage();
        if (image == null) return;
        
        imageView.setFitWidth(image.getWidth() * zoomLevel);
        imageView.setFitHeight(image.getHeight() * zoomLevel);
        
        statusLabel.setText(getImageInfo() + String.format(" | Zoom: %.0f%%", zoomLevel * 100));
    }
    
    private void saveAs() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("imageViewer.saveAs"));
        
        if (isRemoteFile) {
            fileChooser.setInitialFileName(new File(remotePath).getName());
        } else {
            fileChooser.setInitialFileName(localPath.getFileName().toString());
            fileChooser.setInitialDirectory(localPath.getParent().toFile());
        }
        
        // Add file extension filters
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("PNG Images", "*.png"),
            new FileChooser.ExtensionFilter("JPEG Images", "*.jpg", "*.jpeg"),
            new FileChooser.ExtensionFilter("GIF Images", "*.gif"),
            new FileChooser.ExtensionFilter("WebP Images", "*.webp"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        
        File file = fileChooser.showSaveDialog(getTabPane().getScene().getWindow());
        if (file != null) {
            try {
                Files.write(file.toPath(), imageData);
                statusLabel.setText(I18n.get("editor.status.saved", file.getAbsolutePath()));
                logger.info("Saved image as: {}", file.getAbsolutePath());
            } catch (Exception e) {
                logger.error("Failed to save image", e);
                showError(I18n.get("error.title"), I18n.get("editor.error.save", e.getMessage()));
            }
        }
    }
    
    private String getImageInfo() {
        Image image = imageView.getImage();
        if (image == null) return "";
        
        return String.format("%.0f x %.0f px | %.1f KB", 
            image.getWidth(), 
            image.getHeight(),
            imageData.length / 1024.0);
    }
    
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    public String getFilePath() {
        return isRemoteFile ? remotePath : localPath.toString();
    }
    
    public boolean isRemote() {
        return isRemoteFile;
    }
    
    public double getZoomLevel() {
        return zoomLevel;
    }
    
    /**
     * Creates a SessionState for saving this image viewer tab.
     */
    public SessionState createSessionState() {
        SessionState state = new SessionState();
        state.setTabType(SessionState.TabType.IMAGE_VIEWER);
        state.setImageFilePath(isRemoteFile ? remotePath : localPath.toString());
        state.setImageIsRemote(isRemoteFile);
        state.setImageZoomLevel(zoomLevel);
        state.setTabTitle(getText());
        
        if (isRemoteFile && sftpSession != null) {
            // Store connection info
            state.setConnectionId(null); // Will be set by caller if needed
        }
        
        return state;
    }
    
    /**
     * Sets the connection ID for this viewer tab (used during project save).
     */
    public void setConnectionIdForState(SessionState state, String connectionId) {
        if (state != null && isRemoteFile) {
            state.setConnectionId(connectionId);
        }
    }
    
    public SFTPSession getSftpSession() {
        return sftpSession;
    }
}

