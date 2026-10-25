package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.AppDesign;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GlobalSettings;
import de.kortty.model.JumpServer;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's Connection Manager screenshots: the list
 * ({@code app-docs/screenshots/main/connection-manager.png}) and the map
 * ({@code connection-manager-map.png}). Builds the REAL {@link ConnectionManagerDialog} against an
 * isolated {@code user.home} with throw-away demo connections — folders with tab colors, tags, a
 * jump server, a tunnel, favorites — selects one connection and snapshots the dialog pane at 2x.
 * Only documentation hosts ({@code .example}, 192.0.2.x); no credential.
 *
 * <p>Run via the {@code generateConnectionManagerScreenshots} Gradle task. Properties:
 * {@code kortty.screenshot.design} (an {@link AppDesign} id, default {@code normal}) and
 * {@code kortty.screenshot.out} (default {@code app-docs/screenshots/main}). Exit 0 = OK.</p>
 */
public final class ConnectionManagerScreenshotGenerator {

    private static final double WIDTH = 1280;
    private static final double HEIGHT = 760;

    private ConnectionManagerScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-connection-manager-screenshot");
        // Set before the application class is touched: its static initializer resolves the config dir.
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                generate(done, failure);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });
        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            Runtime.getRuntime().halt(2);
        }
        if (failure.get() != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + failure.get());
            Runtime.getRuntime().halt(1);
        }
        Runtime.getRuntime().halt(0);
    }

    private static void generate(CountDownLatch done, AtomicReference<String> failure) throws Exception {
        KorTTYApplication app = new KorTTYApplication();
        app.init(); // singleton + managers; no GUI, no master-password prompt

        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        settings.setLanguage(System.getProperty("kortty.screenshot.language", "en"));
        LanguageManager.getInstance().initialize(settings);
        AppDesign design = AppDesign.fromId(System.getProperty("kortty.screenshot.design", "normal"));
        settings.setAppDesign(design);
        AppDesignStyleSupport.initializeGlobalStyling(design);

        List<ServerConnection> demo = seedDemoConnections(settings);
        for (ServerConnection connection : demo) {
            app.getConfigManager().addConnection(connection);
        }

        // The manager takes its look from its owner's scene; the owner itself is never shown.
        Stage owner = new Stage();
        owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.Pane(), 10, 10));
        ConnectionManagerDialog dialog = new ConnectionManagerDialog(owner, app);
        DialogThemeHelper.applyTheme(dialog);
        DialogPane pane = dialog.getDialogPane();
        pane.setMinSize(WIDTH, HEIGHT);
        pane.setPrefSize(WIDTH, HEIGHT);
        pane.setMaxSize(WIDTH, HEIGHT);
        dialog.show();
        Stage stage = (Stage) pane.getScene().getWindow();
        stage.setX(-4000);
        stage.setY(-4000); // off-screen: no window flash during generation

        String out = System.getProperty("kortty.screenshot.out", "app-docs/screenshots/main");
        PauseTransition settle = new PauseTransition(Duration.millis(1200));
        settle.setOnFinished(event -> {
            try {
                ConnectionManagerTreeView tree = (ConnectionManagerTreeView) field(dialog, "treeView");
                addTeamwork(dialog);
                ServerConnection web = local(dialog, "web-01");
                tree.selectConnection(web);
                settleThen(pane, () -> {
                    capture(pane, new File(out, "connection-manager.png"));
                    invoke(dialog, "setMapMode", true);
                    settleThen(pane, () -> {
                        capture(pane, new File(out, "connection-manager-map.png"));
                        String pdf = System.getProperty("kortty.screenshot.pdf");
                        if (pdf != null) {
                            // The map's PDF export exactly as its button writes it, for a look at the paper version.
                            ConnectionMapView map = (ConnectionMapView) field(dialog, "mapView");
                            ConnectionMapPdfWriter.write(java.nio.file.Path.of(pdf), map.pdfRequest("Connection map", true));
                            System.out.println("Generated " + pdf);
                        }
                        dialog.close();
                        done.countDown();
                    }, failure, done);
                }, failure, done);
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });
        settle.play();
    }

    private interface Step {
        void run() throws Exception;
    }

    private static void settleThen(DialogPane pane, Step step, AtomicReference<String> failure, CountDownLatch done) {
        pane.applyCss();
        pane.layout();
        PauseTransition paint = new PauseTransition(Duration.millis(700));
        paint.setOnFinished(e -> {
            try {
                step.run();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });
        paint.play();
    }

    private static void capture(DialogPane pane, File file) throws Exception {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = pane.snapshot(params, null);
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        System.out.println("Generated " + file.getAbsolutePath() + " (" + (int) image.getWidth() + "x"
            + (int) image.getHeight() + ")");
    }

    /** Folders with tab colors, tags, a jump server with three servers behind it, a tunnel, favorites. */
    private static List<ServerConnection> seedDemoConnections(GlobalSettings settings) {
        long now = System.currentTimeMillis();
        List<ServerConnection> list = new ArrayList<>();
        ServerConnection bastion = ssh(list, "bastion", "bastion.example", 2222, "ops", "Production", "infra", now - 12 * 60_000L, 212);
        ServerConnection web = ssh(list, "web-01", "web-01.prod.example", 22, "deploy", "Production", "prod", now - 12 * 60_000L, 148);
        ssh(list, "web-02", "web-02.prod.example", 22, "deploy", "Production", "prod", now - 26 * 3_600_000L, 96);
        ServerConnection db = ssh(list, "db-primary", "db-01.prod.example", 22, "postgres", "Production", "prod", now - 3 * 86_400_000L, 41);
        for (ServerConnection behind : list.subList(1, list.size())) {
            JumpServer jump = new JumpServer("bastion.example", 2222, "ops");
            jump.setEnabled(true);
            behind.setJumpServer(jump);
        }
        SSHTunnel tunnel = new SSHTunnel();
        tunnel.setEnabled(true);
        tunnel.setType(TunnelType.LOCAL);
        tunnel.setLocalPort(15432);
        tunnel.setRemoteHost("localhost");
        tunnel.setRemotePort(5432);
        db.setSshTunnels(new ArrayList<>(List.of(tunnel)));
        db.setIsolationLevel(de.kortty.isolation.IsolationLevel.values()[de.kortty.isolation.IsolationLevel.values().length - 1]);
        ssh(list, "api-staging", "api.stage.example", 22, "deploy", "Staging", "staging", now - 2 * 3_600_000L, 63);
        ServerConnection worker = ssh(list, "worker-staging", "worker.stage.example", 22, "deploy", "Staging", "staging", now - 5 * 86_400_000L, 22);
        worker.setProtocol(ConnectionProtocol.MOSH);
        ServerConnection devbox = ssh(list, "devbox", "devbox.lab.example", 22, "dev", "Development", "dev", now - 40 * 60_000L, 301);
        devbox.setProtocol(ConnectionProtocol.MOSH);
        ssh(list, "build-runner", "runner-01.lab.example", 22, "ci", "Development", "dev", now - 5 * 86_400_000L, 17);
        ServerConnection sw = ssh(list, "core-switch-01", "192.0.2.10", 22, "admin", "Network", "net", now - 14 * 86_400_000L, 9);
        sw.setDisableHostKeyCheck(Boolean.TRUE);
        ssh(list, "firewall-edge", "192.0.2.1", 22, "admin", "Network", "net", 0, 5);
        ssh(list, "acme-app-01", "app01.acme.example", 22, "acme", "Customer ACME/Prod", "customer", now - 4 * 86_400_000L, 28);
        ServerConnection files = ssh(list, "acme-files", "files.acme.example", 22, "transfer", "Customer ACME/Files", "customer", now - 9 * 86_400_000L, 11);
        files.setIncognito(true);
        ServerConnection shell = ssh(list, "Local zsh", null, 0, null, "Local", null, now - 5 * 3_600_000L, 77);
        shell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        shell.setLocalShellCommand("/bin/zsh");

        // OS symbols on most servers, as a user would set them.
        String[][] badges = {{"bastion", "rocky"}, {"web-01", "redhat"}, {"web-02", "redhat"}, {"db-primary", "redhat"},
            {"api-staging", "ubuntu"}, {"worker-staging", "debian"}, {"devbox", "arch"}, {"build-runner", "suse"},
            {"acme-app-01", "windows-server"}, {"acme-files", "windows-11"}};
        for (String[] pair : badges) {
            list.stream().filter(c -> pair[0].equals(c.getName())).forEach(c -> c.setOsBadge(pair[1]));
        }
        for (ServerConnection connection : list) {
            for (int day = 0; day < 14; day += 1 + (connection.getName().length() % 3)) {
                for (int n = 0; n < 1 + (day + connection.getName().length()) % 4; n++) {
                    connection.setUsageHistory(ServerConnection_withUsage(connection.getUsageHistory(),
                        java.time.LocalDate.now().minusDays(day)));
                }
            }
        }
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("Production", "#e5534b");
        colors.put("Staging", "#dc9825");
        colors.put("Development", "#3fb68b");
        colors.put("Network", "#a56ae6");
        colors.put("Customer ACME", "#13a8ad");
        settings.setConnectionGroupColors(colors);
        settings.setFavoriteConnectionIds(new ArrayList<>(List.of(bastion.getId(), web.getId(), devbox.getId())));
        return list;
    }

    /** The history with one more connection on {@code day} (ServerConnection keeps its helper package-private). */
    private static String ServerConnection_withUsage(String history, java.time.LocalDate day) {
        java.util.TreeMap<java.time.LocalDate, Integer> counts = new java.util.TreeMap<>();
        if (history != null && !history.isBlank()) {
            for (String part : history.split(";")) {
                String[] kv = part.split("=");
                counts.put(java.time.LocalDate.parse(kv[0]), Integer.parseInt(kv[1]));
            }
        }
        counts.merge(day, 1, Integer::sum);
        StringBuilder out = new StringBuilder();
        counts.forEach((d, n) -> out.append(out.length() > 0 ? ";" : "").append(d).append('=').append(n));
        return out.toString();
    }

    /** Two shared connections, as a teamwork source would bring them. */
    @SuppressWarnings("unchecked")
    private static void addTeamwork(ConnectionManagerDialog dialog) throws Exception {
        List<ServerConnection> shared = (List<ServerConnection>) field(dialog, "teamworkConnections");
        ServerConnection monitoring = new ServerConnection();
        monitoring.setName("monitoring");
        monitoring.setHost("mon.team.example");
        monitoring.setUsername("ops");
        monitoring.setGroup("Ops");
        monitoring.setTag("infra");
        monitoring.setOsBadge("debian");
        ServerConnection logs = new ServerConnection();
        logs.setName("log-collector");
        logs.setHost("logs.team.example");
        logs.setUsername("ops");
        logs.setGroup("Ops");
        logs.setOsBadge("ubuntu");
        shared.addAll(List.of(monitoring, logs));
        Method apply = ConnectionManagerDialog.class.getDeclaredMethod("applyFilters");
        apply.setAccessible(true);
        apply.invoke(dialog);
    }

    private static ServerConnection ssh(List<ServerConnection> list, String name, String host, int port, String user,
                                        String group, String tag, long lastUsed, int uses) {
        ServerConnection connection = new ServerConnection();
        connection.setName(name);
        connection.setHost(host);
        connection.setPort(port);
        connection.setUsername(user);
        connection.setGroup(group);
        connection.setTag(tag);
        connection.setLastUsed(lastUsed);
        connection.setUsageCount(uses);
        list.add(connection);
        return connection;
    }

    private static ServerConnection local(ConnectionManagerDialog dialog, String name) throws Exception {
        for (Object candidate : (List<?>) field(dialog, "connections")) {
            if (candidate instanceof ServerConnection connection && name.equals(connection.getName())) {
                return connection;
            }
        }
        throw new IllegalStateException("no demo connection " + name);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invoke(Object target, String name, boolean value) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, boolean.class);
        method.setAccessible(true);
        method.invoke(target, value);
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
