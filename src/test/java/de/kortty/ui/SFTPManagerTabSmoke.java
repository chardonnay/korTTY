package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AuthMethod;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpFileItem;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Drives a real {@link SFTPManagerTab} against a loopback SFTP server: the background listing,
 * a failed navigation that keeps the folder, Size sorted by bytes, uploading a folder twice,
 * wildcard search, Rename and New Folder on both sides, the transfers behind drag and drop, the
 * Disconnected state with Reconnect after the server stops, a quiet tab close, and tabs restored
 * from a project at their saved folders (or the home folders when those are gone). Runs in an
 * isolated {@code user.home}, so the host-key pin it accepts never reaches the real trust store.
 *
 * <p>Run with {@code ./gradlew sftpManagerTabSmoke}.
 */
public final class SFTPManagerTabSmoke {

    private static final long TIMEOUT_MS = 20_000;

    private static Path home;
    private static Path remoteRoot;
    private static Path hostKey;
    private static SshServer server;
    private static SFTPManagerTab tab;

    private SFTPManagerTabSmoke() {
    }

    public static void main(String[] args) throws Exception {
        home = Files.createTempDirectory("kortty-sftp-tab-smoke");
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);
        remoteRoot = Files.createDirectory(home.resolve("remote"));
        hostKey = home.resolve("host.ser");
        prepareRemoteTree();
        server = startServer(0);
        int port = server.getPort();

        Platform.startup(() -> { });
        int exit = 0;
        try {
            run(port);
            System.out.println("SFTPManagerTabSmoke OK");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("SFTPManagerTabSmoke FAILURE: " + failure.getMessage());
            exit = 1;
        } finally {
            if (server != null) {
                server.stop(true);
            }
            Platform.exit();
        }
        System.exit(exit);
    }

    private static void run(int port) throws Exception {
        fx(() -> {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);
            ServerConnection connection = new ServerConnection("smoke", "127.0.0.1", port, "tester");
            connection.setAuthMethod(AuthMethod.PASSWORD);
            TabPane tabPane = new TabPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(tabPane, 1200, 700));
            tab = new SFTPManagerTab(null, connection, "secret", null, 0, null);
            tabPane.getTabs().add(tab);
            stage.show();
            return null;
        });

        // First use of the loopback host key: answer the trust prompt.
        clickInDialog(ButtonType.YES);
        waitFor("connected", () -> status().equals(I18n.get("sftp.connectedTo", "127.0.0.1")));
        waitFor("home listed", () -> "/".equals(remotePath()) && names().contains("many"));
        check(!fx(() -> reconnectButton().isVisible()), "Reconnect must be hidden while connected");

        // A large folder lists in the background and replaces the table only when done.
        call("navigateRemote", "/many");
        waitFor("large folder listed", () -> "/many".equals(remotePath()) && items().size() == 3001);
        check("..".equals(fx(() -> remoteTable().getItems().get(0).getName())), "'..' must stay on top");
        check(!fx(() -> overlay().isVisible()), "the loading overlay must be hidden after the listing");

        // A failed listing keeps the folder that is shown.
        call("navigateRemote", "/does-not-exist");
        clickInDialog(ButtonType.OK);
        waitFor("path field restored", () -> "/many".equals(remotePath()));
        check(fx(() -> items().size()) == 3001, "the old listing must stay after a failure");

        // Size sorts by bytes, folders first.
        call("navigateRemote", "/sized");
        waitFor("sized folder listed", () -> "/sized".equals(remotePath()));
        fx(() -> {
            TableView<SftpFileItem> table = remoteTable();
            TableColumn<SftpFileItem, ?> size = table.getColumns().get(2);
            size.setSortType(TableColumn.SortType.DESCENDING);
            table.getSortOrder().setAll(List.of(size));
            table.sort();
            return null;
        });
        List<String> bySize = fx(() -> remoteTable().getItems().stream().map(SftpFileItem::getName).toList());
        check(bySize.equals(List.of("..", "dir", "big", "nine-k", "two-k", "ten")),
            "Size descending must sort by bytes, got " + bySize);
        snapshot("size-sorted");

        // Upload a folder, change it, upload it again: the second upload merges.
        Path project = Files.createDirectories(home.resolve("project/sub"));
        Files.writeString(home.resolve("project/a.txt"), "first", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("b.txt"), "b", StandardCharsets.UTF_8);
        call("navigateRemote", "/");
        waitFor("root listed", () -> "/".equals(remotePath()));
        call("navigateLocal", home.toString());
        uploadLocalItem("project");
        waitFor("first upload", () -> "first".equals(readRemote("project/a.txt")));
        Files.writeString(home.resolve("project/a.txt"), "second", StandardCharsets.UTF_8);
        uploadLocalItem("project");
        waitFor("second upload merged", () -> "second".equals(readRemote("project/a.txt"))
            && status().equals(I18n.get("sftp.uploadComplete", "1")));
        check(noDialogOpen(), "a second upload of the same folder must not fail");

        runFileOperations();

        // The server goes away: Disconnected with Reconnect, remote actions off.
        server.stop(true);
        waitFor("disconnected state", () -> status().equals(I18n.get("sftp.status.disconnected", "127.0.0.1"))
            && reconnectButton().isVisible());
        call("refreshRemote");
        check(status().equals(I18n.get("sftp.status.disconnected", "127.0.0.1")),
            "Refresh while disconnected must keep the Disconnected state");
        check(fx(() -> ((Button) field("reconnectButton")).isManaged()), "Reconnect must take part in the layout");
        snapshot("disconnected");

        // Same server, same host key: Reconnect works without a new trust prompt.
        server = startServer(port);
        fx(() -> {
            reconnectButton().fire();
            return null;
        });
        waitFor("reconnected", () -> status().equals(I18n.get("sftp.connectedTo", "127.0.0.1"))
            && !reconnectButton().isVisible());
        check(noDialogOpen(), "an unchanged host key must not prompt again");
        waitFor("listing after reconnect", () -> "/".equals(remotePath()) && names().contains("project"));

        // Closing the tab closes the session deliberately: no Disconnected state afterwards.
        call("cleanup");
        server.stop(true);
        server = null;
        TimeUnit.MILLISECONDS.sleep(1500);
        check(!status().equals(I18n.get("sftp.status.disconnected", "127.0.0.1")),
            "a closed tab must not report a lost connection");

        runRestore(port);
    }

    /**
     * Wildcard search, New Folder and Rename on both sides (with an existing and an unusable name),
     * and the transfers behind drag and drop: remote rows dropped on the local panel, local files
     * dropped on a remote folder row, desktop files dropped on the local panel, and the temporary
     * copies a drag out of the window offers. Ends in the remote root, where the steps after it
     * expect the tab.
     */
    private static void runFileOperations() throws Exception {
        call("navigateRemote", "/project");
        waitFor("project listed", () -> "/project".equals(remotePath()) && names().contains("a.txt"));
        call("navigateLocal", home.resolve("project").toString());
        waitFor("local project listed", () -> localNames().contains("a.txt"));

        // Search: a glob over the whole name, '..' stays.
        fx(() -> {
            ((TextField) field("remoteSearchField")).setText("*.TXT");
            return null;
        });
        check(fx(() -> remoteTable().getItems().stream().map(SftpFileItem::getName).toList())
            .equals(List.of("..", "a.txt")), "'*.TXT' must show '..' and a.txt only");
        fx(() -> {
            ((TextField) field("remoteSearchField")).setText("");
            return null;
        });

        // New Folder on the server; the same name again is an error, '..' is no name.
        callAsync("createRemoteFolder");
        answerNameDialog("fresh");
        waitFor("remote folder created", () -> Files.isDirectory(remoteRoot.resolve("project/fresh"))
            && names().contains("fresh"));
        callAsync("createRemoteFolder");
        answerNameDialog("fresh");
        closeDialogShowing(I18n.get("sftp.error.nameExists", "fresh"));
        callAsync("createRemoteFolder");
        typeIntoNameDialog("..");
        clickOkInNameDialog();
        closeDialogShowing(I18n.get("sftp.error.invalidName", ".."));
        // The name dialog stays open with the unusable name; cancel it.
        clickInDialog(ButtonType.CANCEL);
        waitFor("dialogs closed", SFTPManagerTabSmoke::noDialogOpenQuietly);

        // Rename on the server, never over an existing name.
        fx(() -> select(remoteTable(), "a.txt"));
        callAsync("renameRemoteSelected");
        answerNameDialog("renamed.txt");
        waitFor("remote rename", () -> Files.exists(remoteRoot.resolve("project/renamed.txt"))
            && !Files.exists(remoteRoot.resolve("project/a.txt")) && names().contains("renamed.txt"));
        fx(() -> select(remoteTable(), "renamed.txt"));
        callAsync("renameRemoteSelected");
        answerNameDialog("sub");
        closeDialogShowing("renamed.txt: " + I18n.get("sftp.error.nameExists", "sub"));
        check(Files.exists(remoteRoot.resolve("project/renamed.txt")), "a refused rename must keep the file");

        // Local New Folder and Rename.
        callAsync("createLocalFolder");
        answerNameDialog("made-here");
        waitFor("local folder created", () -> Files.isDirectory(home.resolve("project/made-here")));
        fx(() -> select(localTable(), "a.txt"));
        callAsync("renameLocalSelected");
        answerNameDialog("local-renamed.txt");
        waitFor("local rename", () -> Files.exists(home.resolve("project/local-renamed.txt"))
            && !Files.exists(home.resolve("project/a.txt")));

        // Remote rows dropped on the local panel: downloaded into the folder, folders included.
        Path dropped = Files.createDirectories(home.resolve("dropped"));
        List<SftpFileItem> remoteRows = fx(() -> remoteTable().getItems().stream()
            .filter(item -> "renamed.txt".equals(item.getName()) || "sub".equals(item.getName()))
            .toList());
        callWith("downloadItems", new Class<?>[] {List.class, Path.class}, remoteRows, dropped);
        waitFor("drop download", () -> "second".equals(readLocal(dropped.resolve("renamed.txt")))
            && "b".equals(readLocal(dropped.resolve("sub/b.txt"))));

        // Local files dropped on a remote folder row: uploaded into that folder.
        callWith("uploadPaths", new Class<?>[] {List.class, String.class},
            List.of(home.resolve("project/local-renamed.txt")), "/project/fresh");
        waitFor("drop upload into folder row", () -> Files.exists(remoteRoot.resolve("project/fresh/local-renamed.txt")));

        // Desktop files dropped on the local panel: copied, an existing name gets " (2)".
        Path outside = Files.writeString(home.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        callWith("copyIntoLocal", new Class<?>[] {List.class, Path.class}, List.of(outside.toFile()), dropped);
        waitFor("desktop drop copied", () -> "outside".equals(readLocal(dropped.resolve("outside.txt"))));
        callWith("copyIntoLocal", new Class<?>[] {List.class, Path.class}, List.of(outside.toFile()), dropped);
        waitFor("second desktop drop renamed", () -> "outside".equals(readLocal(dropped.resolve("outside (2).txt"))));

        // A drag out of the window: small files are prepared as temporary copies, folders are not.
        SftpFileItem renamed = fx(() -> remoteTable().getItems().stream()
            .filter(item -> "renamed.txt".equals(item.getName())).findFirst().orElseThrow());
        @SuppressWarnings("unchecked")
        List<java.io.File> prepared = (List<java.io.File>) callWith("prepareDragOut",
            new Class<?>[] {List.class}, List.of(renamed));
        check(prepared.size() == 1 && "second".equals(readLocal(prepared.get(0).toPath())),
            "a small remote file must be prepared for the desktop, got " + prepared);
        Path tempFolder = prepared.get(0).toPath().getParent();
        SftpFileItem folder = fx(() -> remoteTable().getItems().stream()
            .filter(item -> "sub".equals(item.getName())).findFirst().orElseThrow());
        @SuppressWarnings("unchecked")
        List<java.io.File> none = (List<java.io.File>) callWith("prepareDragOut",
            new Class<?>[] {List.class}, List.of(folder));
        check(none.isEmpty(), "a folder must not be prepared for the desktop");
        check(!Files.exists(tempFolder), "the previous drag's copies must be gone at the next drag");
        check(status().equals(I18n.get("sftp.dragOut.tooLarge", "20", "16.0 MB")),
            "a folder drag must say why it stays inside the window, got: " + status());
        snapshot("file-operations");

        call("navigateLocal", home.toString());
        call("navigateRemote", "/");
        waitFor("back in the root", () -> "/".equals(remotePath()) && names().contains("project"));
    }

    /**
     * A tab restored from a project starts in its saved folders; a saved remote folder that is gone
     * shows the login directory with a status message instead of an error.
     */
    private static void runRestore(int port) throws Exception {
        server = startServer(port);
        Path savedLocal = home.resolve("project");
        openRestoredTab(port, savedLocal.toString(), "/sized");
        waitFor("restored remote folder", () -> "/sized".equals(remotePath()) && names().contains("big"));
        check(fx(() -> ((TextField) field("localPathField")).getText()).equals(savedLocal.toString()),
            "the restored tab must show the saved local folder");
        check(status().equals(I18n.get("sftp.connectedTo", "127.0.0.1")), "a restored folder needs no message");
        call("cleanup");

        openRestoredTab(port, home.resolve("gone").toString(), "/gone");
        waitFor("fallback to the login directory", () -> "/".equals(remotePath()) && names().contains("sized"));
        check(status().equals(I18n.get("sftp.restore.remotePathMissing", "/gone")),
            "a missing restored folder must be reported in the status bar, got: " + status());
        check(noDialogOpen(), "a missing restored folder must not open an error dialog");
        check(fx(() -> ((TextField) field("localPathField")).getText()).equals(home.toString()),
            "a missing local folder must fall back to the home folder");
        snapshot("restore-fallback");
        call("cleanup");
        server.stop(true);
        server = null;
    }

    private static void openRestoredTab(int port, String localPath, String remotePath) throws Exception {
        fx(() -> {
            TabPane tabPane = tab.getTabPane();
            ServerConnection connection = new ServerConnection("smoke", "127.0.0.1", port, "tester");
            connection.setAuthMethod(AuthMethod.PASSWORD);
            SFTPManagerTab previous = tab;
            tab = new SFTPManagerTab(null, connection, "secret", null, 0, null, localPath, remotePath);
            tabPane.getTabs().remove(previous);
            tabPane.getTabs().add(tab);
            return null;
        });
    }

    /** Saves the window as a PNG when {@code KORTTY_SFTP_SMOKE_SNAPSHOT_DIR} is set (for a visual check). */
    private static void snapshot(String name) throws Exception {
        String directory = System.getenv("KORTTY_SFTP_SMOKE_SNAPSHOT_DIR");
        if (directory == null || directory.isBlank()) {
            return;
        }
        javafx.scene.image.WritableImage image = fx(() -> tab.getTabPane().getScene().snapshot(null));
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(out, "png", Path.of(directory, "sftp-tab-" + name + ".png").toFile());
    }

    private static void prepareRemoteTree() throws Exception {
        Path many = Files.createDirectory(remoteRoot.resolve("many"));
        for (int i = 0; i < 3000; i++) {
            Files.writeString(many.resolve("f" + i + ".txt"), "x", StandardCharsets.UTF_8);
        }
        Path sized = Files.createDirectory(remoteRoot.resolve("sized"));
        Files.write(sized.resolve("ten"), new byte[10]);
        Files.write(sized.resolve("two-k"), new byte[2_048]);
        Files.write(sized.resolve("nine-k"), new byte[9_728]);
        Files.write(sized.resolve("big"), new byte[1_500_000]);
        Files.createDirectory(sized.resolve("dir"));
    }

    private static SshServer startServer(int port) throws Exception {
        SshServer started = SshServer.setUpDefaultServer();
        started.setHost("127.0.0.1");
        started.setPort(port);
        started.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        started.setCipherFactories(List.of(BuiltinCiphers.aes128gcm));
        started.setPasswordAuthenticator((username, password, session) -> "secret".equals(password));
        started.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        started.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        started.start();
        return started;
    }

    private static void uploadLocalItem(String name) throws Exception {
        waitFor("local item " + name, () -> localTable().getItems().stream().anyMatch(i -> name.equals(i.getName())));
        waitFor("upload enabled", () -> {
            selectLocal(name);
            return true;
        });
        call("uploadSelected");
    }

    private static void selectLocal(String name) {
        TableView<SftpFileItem> table = localTable();
        table.getSelectionModel().clearSelection();
        for (SftpFileItem item : table.getItems()) {
            if (name.equals(item.getName())) {
                table.getSelectionModel().select(item);
            }
        }
    }

    private static List<String> localNames() {
        return localTable().getItems().stream().map(SftpFileItem::getName).toList();
    }

    private static Boolean select(TableView<SftpFileItem> table, String name) {
        table.getSelectionModel().clearSelection();
        for (SftpFileItem item : table.getItems()) {
            if (name.equals(item.getName())) {
                table.getSelectionModel().select(item);
                return true;
            }
        }
        throw new IllegalStateException("no row " + name);
    }

    private static String readLocal(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The open name dialog of Rename or New Folder (a TextInputDialog), or {@code null}. */
    private static DialogPane nameDialog() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane pane
                    && pane.lookup(".text-field") instanceof TextField) {
                return pane;
            }
        }
        return null;
    }

    private static void typeIntoNameDialog(String name) throws Exception {
        waitFor("name dialog", () -> nameDialog() != null);
        fx(() -> {
            ((TextField) nameDialog().lookup(".text-field")).setText(name);
            return null;
        });
    }

    private static void clickOkInNameDialog() throws Exception {
        fx(() -> {
            ((Button) nameDialog().lookupButton(ButtonType.OK)).fire();
            return null;
        });
    }

    private static void answerNameDialog(String name) throws Exception {
        typeIntoNameDialog(name);
        clickOkInNameDialog();
        waitFor("name dialog closed", () -> nameDialog() == null);
    }

    /** Closes the alert whose text is {@code text}; fails when no such alert appears. */
    private static void closeDialogShowing(String text) throws Exception {
        waitFor("dialog saying: " + text, () -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane
                        && text.equals(pane.getContentText())) {
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    return true;
                }
            }
            return false;
        });
    }

    /** On the FX thread: whether no dialog is open. */
    private static boolean noDialogOpenQuietly() {
        return Window.getWindows().stream().noneMatch(window -> window.isShowing()
            && window.getScene() != null && window.getScene().getRoot() instanceof DialogPane);
    }

    /** Starts {@code method} on the FX thread without waiting: it may open a dialog and wait for it. */
    private static void callAsync(String method) {
        Platform.runLater(() -> {
            try {
                Method target = SFTPManagerTab.class.getDeclaredMethod(method);
                target.setAccessible(true);
                target.invoke(tab);
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }
        });
    }

    /** Calls {@code method} with declared parameter types on the FX thread and returns its result. */
    private static Object callWith(String method, Class<?>[] types, Object... args) throws Exception {
        return fx(() -> {
            Method target = SFTPManagerTab.class.getDeclaredMethod(method, types);
            target.setAccessible(true);
            return target.invoke(tab, args);
        });
    }

    private static String readRemote(String relative) {
        try {
            Path path = remoteRoot.resolve(relative);
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void clickInDialog(ButtonType buttonType) throws Exception {
        waitFor("dialog with " + buttonType.getText(), () -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    Node button = pane.lookupButton(buttonType);
                    if (button instanceof Button b) {
                        b.fire();
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static boolean noDialogOpen() throws Exception {
        return fx(() -> Window.getWindows().stream().noneMatch(window -> window.isShowing()
            && window.getScene() != null && window.getScene().getRoot() instanceof DialogPane));
    }

    private static String status() {
        Callable<String> read = () -> ((Label) field("statusLabel")).getText();
        if (Platform.isFxApplicationThread()) {
            try {
                return read.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return fxQuiet(read);
    }

    private static String remotePath() {
        return ((TextField) field("remotePathField")).getText();
    }

    @SuppressWarnings("unchecked")
    private static ObservableList<SftpFileItem> items() {
        return (ObservableList<SftpFileItem>) field("remoteItems");
    }

    private static List<String> names() {
        return items().stream().map(SftpFileItem::getName).toList();
    }

    @SuppressWarnings("unchecked")
    private static TableView<SftpFileItem> remoteTable() {
        return (TableView<SftpFileItem>) field("remoteTable");
    }

    @SuppressWarnings("unchecked")
    private static TableView<SftpFileItem> localTable() {
        return (TableView<SftpFileItem>) field("localTable");
    }

    private static Button reconnectButton() {
        return (Button) field("reconnectButton");
    }

    private static Node overlay() {
        return (Node) field("remoteLoadingOverlay");
    }

    private static Object field(String name) {
        try {
            Field field = SFTPManagerTab.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(tab);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void call(String method, Object... args) throws Exception {
        fx(() -> {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i].getClass();
            }
            Method target = SFTPManagerTab.class.getDeclaredMethod(method, types);
            target.setAccessible(true);
            target.invoke(tab, args);
            return null;
        });
    }

    private static void waitFor(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(fxQuiet(condition::getAsBoolean))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what + " (status: " + status() + ")");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T> T fxQuiet(Callable<T> action) {
        try {
            return fx(action);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
