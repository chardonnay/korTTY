package de.kortty.ui.sftp;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Holds UI-facing state for the SFTP dialog.
 */
public final class SftpManagerViewModel {

    private final ObjectProperty<Path> currentLocalPath = new SimpleObjectProperty<>(Paths.get(System.getProperty("user.home")));
    private final StringProperty currentRemotePath = new SimpleStringProperty("~");
    private final StringProperty statusText = new SimpleStringProperty("");
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final ObservableList<SftpFileItem> localItems = FXCollections.observableArrayList();
    private final ObservableList<SftpFileItem> remoteItems = FXCollections.observableArrayList();

    public ObjectProperty<Path> currentLocalPathProperty() {
        return currentLocalPath;
    }

    public Path getCurrentLocalPath() {
        return currentLocalPath.get();
    }

    public void setCurrentLocalPath(Path path) {
        currentLocalPath.set(path);
    }

    public StringProperty currentRemotePathProperty() {
        return currentRemotePath;
    }

    public String getCurrentRemotePath() {
        return currentRemotePath.get();
    }

    public void setCurrentRemotePath(String path) {
        currentRemotePath.set(path);
    }

    public StringProperty statusTextProperty() {
        return statusText;
    }

    public String getStatusText() {
        return statusText.get();
    }

    public void setStatusText(String text) {
        statusText.set(text);
    }

    public BooleanProperty busyProperty() {
        return busy;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void setBusy(boolean value) {
        busy.set(value);
    }

    public BooleanProperty connectedProperty() {
        return connected;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setConnected(boolean value) {
        connected.set(value);
    }

    public ObservableList<SftpFileItem> getLocalItems() {
        return localItems;
    }

    public ObservableList<SftpFileItem> getRemoteItems() {
        return remoteItems;
    }

    public void replaceLocalItems(List<SftpFileItem> items) {
        localItems.setAll(items);
    }

    public void replaceRemoteItems(List<SftpFileItem> items) {
        remoteItems.setAll(items);
    }
}
