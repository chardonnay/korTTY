package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.AuthMethod;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpFileItem;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import javax.imageio.ImageIO;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Renders the SFTP manager for the manual and checks the parts its screenshots show: the real
 * {@link SFTPManagerTab} against a loopback SFTP server with a throwaway demo tree (no real hosts),
 * in an isolated {@code user.home}. The server speaks SFTP 3 like OpenSSH, so it sends owners as
 * numbers; a demo passwd/group answers the name lookup. It checks the sort groups (hidden folders,
 * hidden files, folders, files), the eye button that hides hidden entries, the active panel, the
 * owner names, "Calculate size", the permission checkboxes kept in step with the octal field and
 * the archive dialog's summary and folder-size estimate, then writes
 * {@code sftp-manager.png}, {@code sftp-permissions-dialog.png} and {@code sftp-archive-dialog.png}
 * at 2x (Node.snapshot); with {@code kortty.screenshot.review=true} also
 * {@code sftp-manager-hidden-off.png} and {@code sftp-local-archive-dialog.png}, which the manual
 * does not show.
 *
 * <p>System properties: {@code kortty.screenshot.outDir} (default {@code app-docs/screenshots/sftp}),
 * {@code kortty.screenshot.design} (an {@link AppDesign} id, default {@code normal}) and
 * {@code kortty.screenshot.lang} (default {@code en}). Run via {@code generateSftpManagerScreenshots}.
 * Windows are moved off screen; the run ends itself.
 */
public final class SftpManagerScreenshotGenerator {

    private static final long TIMEOUT_MS = 20_000;
    private static final double WIDTH = 1500;
    private static final double HEIGHT = 820;

    private static Path home;
    private static Path remoteRoot;
    private static Path localDemo;
    private static SFTPManagerTab tab;
    private static Path outDir;

    private SftpManagerScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        // A fixed, short folder keeps the local path in the shot the same on every run (a Mac's
        // default temp folder is long and random); it is this generator's own and starts empty.
        Path tmp = Path.of("/tmp");
        if (Files.isDirectory(tmp)) {
            home = tmp.resolve("kortty-demo");
            deleteTree(home);
            Files.createDirectories(home);
        } else {
            home = Files.createTempDirectory("kortty-demo");
        }
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);
        outDir = Path.of(System.getProperty("kortty.screenshot.outDir", "app-docs/screenshots/sftp")).toAbsolutePath();
        Files.createDirectories(outDir);
        remoteRoot = Files.createDirectories(home.resolve("server"));
        localDemo = Files.createDirectories(home.resolve("work/webshop"));
        prepareDemoTrees();
        SshServer server = startServer(home.resolve("host.ser"));

        int exit = 0;
        try {
            CompletableFuture<Void> started = new CompletableFuture<>();
            Platform.setImplicitExit(false);
            Platform.startup(() -> started.complete(null));
            started.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            run(server.getPort());
            System.out.println("SftpManagerScreenshotGenerator OK -> " + outDir);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.err.println("SftpManagerScreenshotGenerator FAILURE: " + failure.getMessage());
            exit = 1;
        } finally {
            server.stop(true);
        }
        // A dialog's nested event loop can keep System.exit from returning; halt from a thread.
        int code = exit;
        new Thread(() -> Runtime.getRuntime().halt(code)).start();
    }

    private static void run(int port) throws Exception {
        AppDesign design = AppDesign.fromId(System.getProperty("kortty.screenshot.design", "normal"));
        String language = System.getProperty("kortty.screenshot.lang", "en");
        fx(() -> {
            KorTTYApplication app = new KorTTYApplication();
            app.init();
            GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
            settings.setLanguage(language);
            settings.setAppDesign(design);
            LanguageManager.getInstance().initialize(settings);
            AppDesignStyleSupport.initializeGlobalStyling(design);
            // Every window this run opens (the tab's stage, the dialogs) stays off the user's screen.
            Window.getWindows().addListener((javafx.collections.ListChangeListener<Window>) change -> {
                while (change.next()) {
                    for (Window window : change.getAddedSubList()) {
                        window.setX(-6000);
                        window.setY(80);
                        window.setOnShown(event -> {
                            window.setX(-6000);
                            window.setY(80);
                        });
                    }
                }
            });

            ServerConnection connection = new ServerConnection("demo", "127.0.0.1", port, "demo");
            connection.setAuthMethod(AuthMethod.PASSWORD);
            TabPane tabPane = new TabPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(tabPane, WIDTH, HEIGHT));
            tab = new SFTPManagerTab(app, connection, "secret", null, 0, null);
            tabPane.getTabs().add(tab);
            stage.show();
            AppDesignStyleSupport.applyToWindow(stage, design, true);
            return null;
        });

        // First use of the loopback host key: trust it.
        clickInDialog(ButtonType.YES);
        waitFor("connected", () -> remoteTable().getItems().size() > 0);
        call("navigateRemote", "/home/demo");
        call("navigateLocal", localDemo.toString());
        waitFor("remote demo folder", () -> "/home/demo".equals(field(TextField.class, "remotePathField").getText()));
        waitFor("local demo folder", () -> names(localTable()).contains("src"));

        // 1. Sort groups: hidden folders, hidden files, folders, files.
        check(names(remoteTable()).equals(List.of("..", ".cache", ".config", ".ssh", ".bash_history", ".bashrc",
                ".profile", "backups", "projects", "www", "access.log", "deploy.sh", "notes.md", "site-backup.tar.gz")),
            "remote sort groups, got " + names(remoteTable()));
        check(names(localTable()).equals(List.of("..", ".git", ".github", ".env", ".gitignore", "docs", "src",
                "build.gradle.kts", "CHANGELOG.md", "deploy.sh", "README.md")),
            "local sort groups, got " + names(localTable()));

        // 1b. The server sent only numbers; the lookup puts names in their place.
        try {
            waitFor("owner names", () -> "demo".equals(remoteCell(4, "deploy.sh"))
                && "webteam".equals(remoteCell(5, "deploy.sh")));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + ": deploy.sh shows " + fx(() -> remoteCell(4, "deploy.sh"))
                + "/" + fx(() -> remoteCell(5, "deploy.sh")));
        }
        fx(() -> {
            select(remoteTable(), "backups", "www");
            invoke("calculateSelectedFolderSizes", new Class<?>[0]);
            return null;
        });
        waitFor("folder sizes", () -> !"—".equals(remoteCell(2, "backups")) && !"—".equals(remoteCell(2, "www")));
        System.out.println("Measured: backups=" + fx(() -> remoteCell(2, "backups")) + " www=" + fx(() -> remoteCell(2, "www")));

        // 2. The active panel follows the keyboard focus; then remote, with a selection on both sides.
        fx(() -> {
            localTable().requestFocus();
            return null;
        });
        check(fx(() -> isActive("localPanel")) && !fx(() -> isActive("remotePanel")), "focus in the local list");
        fx(() -> {
            select(localTable(), "src");
            select(remoteTable(), "backups", "notes.md", "deploy.sh");
            remoteTable().requestFocus();
            return null;
        });
        check(fx(() -> isActive("remotePanel")), "remote panel must be active");
        check(!fx(() -> isActive("localPanel")), "local panel must not be active");
        settle();
        snapshot(fx(() -> tab.getTabPane()), "sftp-manager.png");

        // 3. The eye button hides the local panel's hidden entries (and only those).
        fx(() -> {
            invoke("setShowHidden", new Class<?>[] {boolean.class, boolean.class}, false, false);
            return null;
        });
        // Filtering the other list must not move the active panel (a table's focused row once did).
        check(fx(() -> isActive("remotePanel")), "hiding local entries must leave the server panel active");
        check(names(localTable()).equals(List.of("..", "docs", "src", "build.gradle.kts", "CHANGELOG.md",
            "deploy.sh", "README.md")), "hidden local entries must disappear, got " + names(localTable()));
        check(names(remoteTable()).contains(".bashrc"), "the remote panel keeps its hidden entries");
        settle();
        review(fx(() -> tab.getTabPane()), "sftp-manager-hidden-off.png");
        fx(() -> {
            invoke("setShowHidden", new Class<?>[] {boolean.class, boolean.class}, false, true);
            return null;
        });

        // 4. Set Owner/Permissions: the checkboxes follow the field and the other way round.
        fx(() -> {
            select(remoteTable(), "deploy.sh");
            return null;
        });
        callAsync("setOwnerPermissionsDialog");
        DialogPane permissions = waitForDialog("permissions", pane -> pane.lookup(".sftp-permission-bits") != null);
        String owner = fx(() -> ((javafx.scene.layout.GridPane) permissions.lookup(".sftp-permission-bits").getParent())
            .getChildren().stream().filter(TextField.class::isInstance).map(node -> ((TextField) node).getText())
            .findFirst().orElse(null));
        check("demo".equals(owner), "the dialog must offer the owner's name, not the UID: " + owner);
        String initial = fx(() -> permissionsField(permissions).getText());
        check("755".equals(initial), "deploy.sh is 755 on the server, field shows " + initial);
        check(fx(() -> checkedBits(permissions)).equals("111101101"), "boxes must show 755");
        fx(() -> {
            permissionsField(permissions).setText("640");
            return null;
        });
        check(fx(() -> checkedBits(permissions)).equals("110100000"), "typing 640 must tick rw-r-----");
        fx(() -> {
            boxes(permissions).get(4).setSelected(true);   // group write
            boxes(permissions).get(2).setSelected(true);   // owner execute
            return null;
        });
        check("760".equals(fx(() -> permissionsField(permissions).getText())), "ticking must write 760");
        check("rwxrw----".equals(fx(() -> symbolic(permissions))), "the rwx reading must follow");
        settle();
        snapshot(permissions, "sftp-permissions-dialog.png");
        close(permissions);

        // 5. The archive dialog says where and what: server, folder, 2 folders and 2 files. A new
        // listing forgets measured sizes, so the dialog measures the two folders itself.
        call("navigateRemote", "/home/demo");
        waitFor("relisted", () -> "—".equals(remoteCell(2, "backups")));
        fx(() -> {
            select(remoteTable(), "backups", "www", "notes.md", "deploy.sh");
            return null;
        });
        callAsync("createRemoteArchive");
        DialogPane archive = waitForDialog("archive", pane -> pane.lookup(".sftp-archive-summary") != null);
        String summary = fx(() -> labels(archive.lookup(".sftp-archive-summary")));
        check(summary.contains("/home/demo") && summary.contains("demo@127.0.0.1"),
            "summary must name the server and folder: " + summary);
        check(summary.contains(I18n.get("sftp.archive.selectionCounts", 2, 2)), "summary must count 2 + 2: " + summary);
        check(fx(() -> archive.getHeaderText()).contains("demo@127.0.0.1"), "header must name the server");
        // The dialog measures the two folders itself; the estimate then counts them (2.4 MB of backups).
        String estimatePrefix = I18n.get("sftp.archive.estimatedSize", "");
        String measuring = I18n.get("sftp.archive.estimatedSizeCalculating", "#", 2);
        String measuringSuffix = measuring.substring(measuring.indexOf('#') + 1);
        waitFor("estimate with the folders", () -> {
            String estimate = labelStartingWith(archive, estimatePrefix);
            return estimate != null && !estimate.endsWith(measuringSuffix);
        });
        String estimate = fx(() -> labelStartingWith(archive, estimatePrefix));
        check(estimate.endsWith(" MB"), "the estimate must include the measured folders: " + estimate);
        System.out.println("Archive estimate: " + estimate);
        settle();
        snapshot(archive, "sftp-archive-dialog.png");
        close(archive);

        // 6. The local archive dialog names this computer instead.
        fx(() -> {
            select(localTable(), "src", "README.md");
            invoke("setActivePane", new Class<?>[] {boolean.class}, false);
            return null;
        });
        callAsync("createLocalArchive");
        DialogPane localArchive = waitForDialog("local archive", pane -> pane.lookup(".sftp-archive-summary") != null);
        String localSummary = fx(() -> labels(localArchive.lookup(".sftp-archive-summary")));
        check(localSummary.contains("webshop"), "local summary must show the folder: " + localSummary);
        check(localSummary.contains(I18n.get("sftp.archive.selectionCounts", 1, 1)), "local counts 1 + 1: " + localSummary);
        settle();
        review(localArchive, "sftp-local-archive-dialog.png");
        close(localArchive);

        // 7. The path as folders: a click on "home" goes there; Cmd/Ctrl+L types a path instead.
        de.kortty.ui.sftp.SftpBreadcrumbBar crumbs = field(de.kortty.ui.sftp.SftpBreadcrumbBar.class, "remoteBreadcrumb");
        check(fx(crumbs::shownCrumbs).stream().map(de.kortty.ui.sftp.SftpBreadcrumbBar.Crumb::label).toList()
            .equals(List.of("/", "home", "demo")), "breadcrumb of /home/demo, got " + fx(crumbs::shownCrumbs));
        fx(() -> {
            for (Node node : crumbs.lookupAll(".sftp-crumb")) {
                if (node instanceof Button button && "home".equals(button.getText())) {
                    button.fire();
                }
            }
            return null;
        });
        waitFor("breadcrumb click", () -> "/home".equals(field(TextField.class, "remotePathField").getText()));
        fx(() -> {
            crumbs.edit();
            TextField path = field(TextField.class, "remotePathField");
            path.setText("/home/demo/www");
            path.fireEvent(new javafx.event.ActionEvent());
            return null;
        });
        waitFor("typed path", () -> "/home/demo/www".equals(field(TextField.class, "remotePathField").getText())
            && names(remoteTable()).contains("index.html"));
        check(!fx(crumbs::isEditing), "Enter must return to the folders");
        check(fx(() -> remoteTable().getScene().getFocusOwner() == remoteTable()), "Enter gives the keyboard back to the list");
        String status = fx(() -> field(Label.class, "remotePaneStatus").getText());
        check(status.startsWith(I18n.get("sftp.paneStatus.entries", 1)), "status line, got " + status);

        // 8. Compare the local webshop with its deployed copy, synchronize to the server, compare again.
        call("navigateRemote", "/srv/webshop");
        waitFor("deployed copy", () -> "/srv/webshop".equals(field(TextField.class, "remotePathField").getText()));
        callAsync("startCompare");
        DialogPane compareDialog = waitForDialog("compare", pane -> pane.lookup(".text-area") != null
            && I18n.get("sftp.compare.header").equals(pane.getHeaderText()));
        fx(() -> {
            for (ButtonType type : compareDialog.getButtonTypes()) {
                if (type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                    ((Button) compareDialog.lookupButton(type)).fire();
                }
            }
            return null;
        });
        waitFor("comparison", () -> field(Label.class, "compareTitle").getText().contains("\u21C4"));
        check("NEWER_LOCAL".equals(compareState(false, "README.md")), "README.md newer here");
        check("NEWER_REMOTE".equals(compareState(true, "deploy.sh")), "deploy.sh newer on the server");
        check("ONLY_LOCAL".equals(compareState(false, "CHANGELOG.md")), "CHANGELOG.md only here");
        // docs exists on both sides (empty here); the note inside it is only on the server.
        check("CONTENT_DIFFERS".equals(compareState(true, "docs")), "docs differs inside");
        check("DIFFERENT".equals(compareState(false, "build.gradle.kts")), "build.gradle.kts different");
        check("SAME".equals(compareState(false, ".env")), ".env the same");
        check("CONTENT_DIFFERS".equals(compareState(false, "src")) || "SAME".equals(compareState(false, "src")),
            "src compared, got " + compareState(false, "src"));
        fx(() -> {
            localTable().getSelectionModel().clearSelection();
            remoteTable().getSelectionModel().clearSelection();
            return null;
        });
        settle();
        snapshot(fx(() -> tab.getTabPane()), "sftp-compare.png");

        Platform.runLater(() -> {
            try {
                invoke("synchronize", new Class<?>[] {boolean.class}, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        // .git, .github, CHANGELOG.md, README.md and build.gradle.kts go up; deploy.sh is newer there.
        String syncHeader = I18n.get("sftp.sync.headerToServer", 5, "#");
        String syncPrefix = syncHeader.substring(0, syncHeader.indexOf('#'));
        DialogPane syncDialog = waitForDialog("synchronize", pane -> pane.getHeaderText() != null
            && pane.getHeaderText().startsWith(syncPrefix));
        settle();
        snapshot(syncDialog, "sftp-sync-dialog.png");
        fx(() -> {
            for (ButtonType type : syncDialog.getButtonTypes()) {
                if (type.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                    ((Button) syncDialog.lookupButton(type)).fire();
                }
            }
            return null;
        });
        // The comparison runs again by itself; what is left are the newer server file and docs/.
        waitFor("compared again after synchronizing", () -> "SAME".equals(compareState(false, "README.md"))
            && "SAME".equals(compareState(false, "CHANGELOG.md")));
        check("SAME".equals(compareState(false, "build.gradle.kts")), "build.gradle.kts copied");
        check("NEWER_REMOTE".equals(compareState(true, "deploy.sh")), "the newer server file stays");
        check(Files.getLastModifiedTime(remoteRoot.resolve("srv/webshop/README.md"))
            .equals(Files.getLastModifiedTime(localDemo.resolve("README.md"))) || Math.abs(
                Files.getLastModifiedTime(remoteRoot.resolve("srv/webshop/README.md")).toMillis()
                - Files.getLastModifiedTime(localDemo.resolve("README.md")).toMillis()) < 1000,
            "the copy keeps the modification time");
        System.out.println("Compared again: " + fx(() -> labels(field(javafx.scene.layout.HBox.class, "compareBar"))));
    }

    /** How the row {@code name} of one list compares, as the state's name, or null. */
    private static String compareState(boolean remote, String name) {
        return fxQuietly(() -> {
            TableView<SftpFileItem> table = remote ? remoteTable() : localTable();
            for (SftpFileItem item : table.getItems()) {
                if (name.equals(item.getName())) {
                    Object state = invoke("comparisonState", new Class<?>[] {SftpFileItem.class, boolean.class},
                        item, remote);
                    return state == null ? null : state.toString();
                }
            }
            return "missing";
        });
    }

    /** Cancels the dialog of {@code pane} and waits until its window is gone. */
    private static void close(DialogPane pane) throws Exception {
        Window window = fx(() -> pane.getScene().getWindow());
        fx(() -> {
            ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            return null;
        });
        waitFor("dialog closed", () -> !window.isShowing());
    }

    // ---- demo data -----------------------------------------------------------------------------

    private static void prepareDemoTrees() throws Exception {
        Path demo = Files.createDirectories(remoteRoot.resolve("home/demo"));
        for (String dir : List.of(".cache", ".config", ".ssh", "backups", "projects", "www")) {
            Files.createDirectories(demo.resolve(dir));
        }
        write(demo.resolve(".bash_history"), 14_600);
        write(demo.resolve(".bashrc"), 3_771);
        write(demo.resolve(".profile"), 807);
        write(demo.resolve("access.log"), 1_258_291);
        write(demo.resolve("deploy.sh"), 2_140);
        write(demo.resolve("notes.md"), 5_320);
        sparse(demo.resolve("site-backup.tar.gz"), 48L * 1024 * 1024);
        Files.setPosixFilePermissions(demo.resolve("deploy.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        write(demo.resolve("backups/db-2026-09-13.sql"), 2_400_000);
        write(demo.resolve("www/index.html"), 18_000);
        // The demo accounts the name lookup finds for the UID and GID the demo files have here
        // (under /tmp a Mac gives new files the folder's group, GID 0).
        int uid = (Integer) Files.getAttribute(demo.resolve("deploy.sh"), "unix:uid");
        int gid = (Integer) Files.getAttribute(demo.resolve("deploy.sh"), "unix:gid");
        Files.writeString(home.resolve("passwd"), "demo:x:" + uid + ":" + gid + ":Demo:/home/demo:/bin/bash\n");
        Files.writeString(home.resolve("group"), "webteam:x:" + gid + ":demo\n");
        Files.setPosixFilePermissions(demo.resolve(".ssh"), PosixFilePermissions.fromString("rwx------"));
        stamp(demo);

        for (String dir : List.of(".git", ".github", "docs", "src")) {
            Files.createDirectories(localDemo.resolve(dir));
        }
        write(localDemo.resolve(".env"), 312);
        write(localDemo.resolve(".gitignore"), 96);
        write(localDemo.resolve("build.gradle.kts"), 4_410);
        write(localDemo.resolve("CHANGELOG.md"), 9_870);
        write(localDemo.resolve("README.md"), 3_020);
        write(localDemo.resolve("deploy.sh"), 1_204);
        stamp(localDemo);
        Files.createDirectories(localDemo.resolve("src/main"));
        write(localDemo.resolve("src/main/App.java"), 1_800);

        // The deployed copy on the server: mostly the same, with one of each kind of difference.
        Path site = Files.createDirectories(remoteRoot.resolve("srv/webshop"));
        long base = LocalDateTime.of(2026, 9, 1, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (Path file : List.of(localDemo.resolve(".env"), localDemo.resolve(".gitignore"),
                localDemo.resolve("src/main/App.java"), localDemo.resolve("deploy.sh"),
                localDemo.resolve("README.md"), localDemo.resolve("build.gradle.kts"))) {
            Files.setLastModifiedTime(file, FileTime.fromMillis(base));
            Path copy = site.resolve(localDemo.relativize(file).toString());
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            Files.setLastModifiedTime(copy, FileTime.fromMillis(base));
        }
        Files.setLastModifiedTime(localDemo.resolve("README.md"), FileTime.fromMillis(base + 86_400_000L));
        Files.setLastModifiedTime(site.resolve("deploy.sh"), FileTime.fromMillis(base + 3_600_000L));
        write(site.resolve("build.gradle.kts"), 4_000);
        Files.setLastModifiedTime(site.resolve("build.gradle.kts"), FileTime.fromMillis(base));
        Files.createDirectories(site.resolve("docs"));
        write(site.resolve("docs/old-notes.md"), 640);
        for (Path dir : List.of(site, site.resolve("src"), site.resolve("src/main"), site.resolve("docs"))) {
            Files.setLastModifiedTime(dir, FileTime.fromMillis(base));
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void write(Path file, int bytes) throws Exception {
        Files.write(file, "#".repeat(bytes).getBytes(StandardCharsets.US_ASCII));
    }

    private static void sparse(Path file, long bytes) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(bytes);
        }
    }

    /** Fixed, plausible dates, so the screenshots do not change with the day they are made. */
    private static void stamp(Path folder) throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 9, 14, 9, 30);
        int index = 0;
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.sorted().toList()) {
                LocalDateTime time = base.minusDays(index * 3L).plusMinutes(index * 37L);
                Files.setLastModifiedTime(entry, FileTime.from(time.atZone(ZoneId.systemDefault()).toInstant()));
                index++;
            }
        }
    }

    /** {@code command} as it runs against the demo tree instead of this computer's own files. */
    private static String demoCommand(String command) {
        String root = remoteRoot.toString();
        if (command.startsWith("'du' ")) {
            // Measure the served folders, then print the paths as the client asked for them.
            return "(" + command.replace("'/", "'" + root + "/") + ") | sed 's#" + root + "##'";
        }
        return command
            .replace("getent passwd", "false")
            .replace("getent group", "false")
            .replace("/etc/passwd", home.resolve("passwd").toString())
            .replace("/etc/group", home.resolve("group").toString());
    }

    private static SshServer startServer(Path hostKey) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setCipherFactories(List.of(BuiltinCiphers.aes128gcm));
        server.setPasswordAuthenticator((username, password, session) -> "secret".equals(password));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        // SFTP 3, as OpenSSH speaks it: owners and groups arrive as UID and GID only.
        org.apache.sshd.sftp.SftpModuleProperties.SFTP_VERSION.set(server, 3);
        // Commands run in a local shell (only this run's client can log in), pointed at the demo
        // data: the name lookup reads a demo passwd/group, du measures the served folders.
        server.setCommandFactory((channel, command) ->
            org.apache.sshd.server.shell.ProcessShellCommandFactory.INSTANCE.createCommand(channel, demoCommand(command)));
        server.start();
        return server;
    }

    // ---- dialog helpers ------------------------------------------------------------------------

    private static TextField permissionsField(DialogPane pane) {
        // The octal field sits in the row with the rwx reading, right above the checkbox grid.
        Node bits = pane.lookup(".sftp-permission-bits");
        javafx.scene.layout.GridPane grid = (javafx.scene.layout.GridPane) bits.getParent();
        for (Node child : grid.getChildren()) {
            if (child instanceof javafx.scene.layout.HBox row && row.getChildren().get(0) instanceof TextField field) {
                return field;
            }
        }
        throw new IllegalStateException("no permissions field");
    }

    private static List<CheckBox> boxes(DialogPane pane) {
        return ((javafx.scene.layout.GridPane) pane.lookup(".sftp-permission-bits")).getChildren().stream()
            .filter(CheckBox.class::isInstance).map(CheckBox.class::cast).toList();
    }

    private static String checkedBits(DialogPane pane) {
        StringBuilder bits = new StringBuilder();
        for (CheckBox box : boxes(pane)) {
            bits.append(box.isSelected() ? '1' : '0');
        }
        return bits.toString();
    }

    private static String symbolic(DialogPane pane) {
        TextField field = permissionsField(pane);
        javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) field.getParent();
        return ((Label) row.getChildren().get(1)).getText();
    }

    private static String labels(Node root) {
        StringBuilder text = new StringBuilder();
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && label.getText() != null) {
                text.append(label.getText()).append(" | ");
            }
        }
        return text.toString();
    }

    private static String labelStartingWith(Node root, String prefix) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && label.getText() != null && label.getText().startsWith(prefix)) {
                return label.getText();
            }
        }
        return null;
    }

    private static DialogPane waitForDialog(String what, java.util.function.Predicate<DialogPane> matches)
            throws Exception {
        DialogPane[] found = new DialogPane[1];
        waitFor(what + " dialog", () -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane && matches.test(pane)) {
                    found[0] = pane;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static void clickInDialog(ButtonType buttonType) throws Exception {
        waitFor("dialog with " + buttonType.getText(), () -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane
                        && pane.lookupButton(buttonType) instanceof Button button) {
                    button.fire();
                    return true;
                }
            }
            return false;
        });
    }

    // ---- tab helpers ---------------------------------------------------------------------------

    private static void select(TableView<SftpFileItem> table, String... names) {
        table.getSelectionModel().clearSelection();
        for (String name : names) {
            boolean found = false;
            for (SftpFileItem item : table.getItems()) {
                if (name.equals(item.getName())) {
                    table.getSelectionModel().select(item);
                    found = true;
                }
            }
            if (!found) {
                throw new IllegalStateException("no row " + name);
            }
        }
    }

    /** The text column {@code column} of the server list shows for the row {@code name}. */
    @SuppressWarnings("unchecked")
    private static String remoteCell(int column, String name) {
        TableView<SftpFileItem> table = remoteTable();
        for (SftpFileItem item : table.getItems()) {
            if (name.equals(item.getName())) {
                Object value = ((javafx.scene.control.TableColumn<SftpFileItem, ?>) table.getColumns().get(column))
                    .getCellData(item);
                return value == null ? null : value.toString();
            }
        }
        return null;
    }

    private static List<String> names(TableView<SftpFileItem> table) {
        Callable<List<String>> read = () -> table.getItems().stream().map(SftpFileItem::getName).toList();
        try {
            return Platform.isFxApplicationThread() ? read.call() : fx(read);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TableView<SftpFileItem> remoteTable() {
        return field(TableView.class, "remoteTable");
    }

    @SuppressWarnings("unchecked")
    private static TableView<SftpFileItem> localTable() {
        return field(TableView.class, "localTable");
    }

    private static boolean isActive(String panel) {
        return field(VBox.class, panel).getPseudoClassStates().stream()
            .anyMatch(state -> "active-pane".equals(state.getPseudoClassName()));
    }

    private static <T> T field(Class<T> type, String name) {
        try {
            Field field = SFTPManagerTab.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(tab));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void call(String method, String argument) throws Exception {
        fx(() -> invoke(method, new Class<?>[] {String.class}, argument));
    }

    private static Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
        Method target = SFTPManagerTab.class.getDeclaredMethod(method, types);
        target.setAccessible(true);
        return target.invoke(tab, args);
    }

    /** Starts {@code method} on the FX thread without waiting: it opens a dialog and waits for it. */
    private static void callAsync(String method) {
        Platform.runLater(() -> {
            try {
                invoke(method, new Class<?>[0]);
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---- snapshots and waiting -----------------------------------------------------------------

    private static void snapshot(Node node, String fileName) throws Exception {
        fx(() -> {
            node.applyCss();
            if (node instanceof javafx.scene.Parent parent) {
                parent.layout();
            }
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.web("#1e1e1e"));
            params.setTransform(Transform.scale(2, 2));
            var image = node.snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", outDir.resolve(fileName).toFile());
            System.out.println("Wrote " + outDir.resolve(fileName));
            return null;
        });
    }

    /** A shot for checking by eye only, written when {@code kortty.screenshot.review} is set. */
    private static void review(Node node, String fileName) throws Exception {
        if (Boolean.getBoolean("kortty.screenshot.review")) {
            snapshot(node, fileName);
        }
    }

    /** Lets a few pulses pass, so CSS, layout and lazily created cells are done before a snapshot. */
    private static void settle() throws Exception {
        TimeUnit.MILLISECONDS.sleep(400);
        fx(() -> null);
    }

    private static void waitFor(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Boolean met;
            try {
                met = fx(condition::getAsBoolean);
            } catch (Exception e) {
                met = false;
            }
            if (Boolean.TRUE.equals(met)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T> T fxQuietly(Callable<T> action) {
        try {
            return fx(action);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }
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
