package de.kortty.ui;

import de.kortty.core.worker.SessionWorkerProcess;
import de.kortty.core.worker.SessionWorkerRegistry;
import de.kortty.isolation.IsolationState;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tools › Session Processes: every session worker that runs now, with its connection, process id,
 * isolation, memory, CPU and running time, refreshed every two seconds. A worker can be ended from
 * here; its tab then reports the connection as lost and offers a reconnect. A row whose worker keeps
 * a CPU core busy is marked.
 */
final class SessionProcessesDialog extends ThemeAwareDialog<Void> {

    /** CPU share from which a row is marked, in percent of one core. */
    static final double BUSY_CPU_PERCENT = 90.0;

    /** One row: a worker and the figures last measured for it. */
    record Row(SessionWorkerProcess worker, String memory, String cpu, boolean busy) {
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Session-Processes-Sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Double> lastCpuSeconds = new ConcurrentHashMap<>();
    private final Runnable registryListener = () -> Platform.runLater(this::sampleSoon);

    SessionProcessesDialog() {
        setTitle(I18n.get("sessionProcesses.title"));
        setHeaderText(I18n.get("sessionProcesses.header"));
        setResizable(true);

        TableView<Row> table = new TableView<>(rows);
        table.setPlaceholder(new Label(I18n.get("sessionProcesses.empty")));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column("sessionProcesses.column.connection", row -> row.worker().displayName()));
        table.getColumns().add(column("sessionProcesses.column.pid", row -> Long.toString(row.worker().pid())));
        table.getColumns().add(column("sessionProcesses.column.isolation",
            row -> stateText(row.worker().isolationState())));
        table.getColumns().add(column("sessionProcesses.column.memory", Row::memory));
        table.getColumns().add(column("sessionProcesses.column.cpu", Row::cpu));
        table.getColumns().add(column("sessionProcesses.column.uptime",
            row -> uptime(Duration.between(row.worker().started(), Instant.now()))));
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("session-process-busy");
                setTooltip(null);
                if (!empty && row != null && row.busy()) {
                    getStyleClass().add("session-process-busy");
                    setTooltip(new javafx.scene.control.Tooltip(I18n.get("sessionProcesses.busy")));
                    setStyle("-fx-background-color: rgba(245, 158, 11, 0.25);");
                } else {
                    setStyle(null);
                }
            }
        });
        table.setPrefSize(820, 320);
        VBox.setVgrow(table, Priority.ALWAYS);

        Button end = new Button(I18n.get("sessionProcesses.end"));
        end.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        end.setOnAction(event -> {
            Row selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                selected.worker().kill();
                sampleSoon();
            }
        });
        Label hint = new Label(I18n.get("sessionProcesses.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        HBox.setHgrow(hint, Priority.ALWAYS);
        HBox actions = new HBox(12, end, hint);

        VBox content = new VBox(10, table, actions);
        content.setPadding(new Insets(12));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        SessionWorkerRegistry.addListener(registryListener);
        sampler.scheduleWithFixedDelay(this::sample, 0, 2, TimeUnit.SECONDS);
        setOnHidden(event -> {
            SessionWorkerRegistry.removeListener(registryListener);
            sampler.shutdownNow();
        });
    }

    private static TableColumn<Row, String> column(String key, java.util.function.Function<Row, String> value) {
        TableColumn<Row, String> column = new TableColumn<>(I18n.get(key));
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private void sampleSoon() {
        sampler.execute(this::sample);
    }

    /** Measures every running worker off the JavaFX thread and shows the result. */
    private void sample() {
        List<SessionWorkerProcess> workers = SessionWorkerRegistry.running();
        List<Row> measured = new java.util.ArrayList<>();
        for (SessionWorkerProcess worker : workers) {
            long pid = worker.pid();
            String memory = residentMemory(pid);
            double cpuPercent = cpuPercent(worker);
            String cpu = cpuPercent >= 0 ? String.format(Locale.ROOT, "%.0f %%", cpuPercent) : "–";
            measured.add(new Row(worker, memory, cpu, cpuPercent >= BUSY_CPU_PERCENT));
        }
        Platform.runLater(() -> rows.setAll(measured));
    }

    /** CPU since the last sample, in percent of one core; -1 before the second sample. */
    private double cpuPercent(SessionWorkerProcess worker) {
        Duration total = worker.handle().info().totalCpuDuration().orElse(null);
        if (total == null) {
            return -1;
        }
        double seconds = total.toNanos() / 1e9;
        Double previous = lastCpuSeconds.put(worker.pid(), seconds);
        return previous == null ? -1 : Math.max(0, (seconds - previous) / 2.0 * 100.0);
    }

    /** The resident memory of {@code pid} from {@code ps} (macOS, Linux), or a dash. */
    static String residentMemory(long pid) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return "–";
        }
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                ps.waitFor(2, TimeUnit.SECONDS);
                long kib = line != null ? Long.parseLong(line.trim()) : -1;
                return kib >= 0 ? String.format(Locale.ROOT, "%.0f MB", kib / 1024.0) : "–";
            }
        } catch (Exception e) {
            return "–";
        }
    }

    static String stateText(IsolationState state) {
        return switch (state) {
            case SANDBOXED -> I18n.get("sessionProcesses.state.sandboxed");
            case DEGRADED -> I18n.get("sessionProcesses.state.degraded");
            case PROCESS, NONE -> I18n.get("sessionProcesses.state.process");
        };
    }

    static String uptime(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
