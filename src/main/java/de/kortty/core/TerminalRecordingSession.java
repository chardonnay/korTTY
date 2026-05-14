package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.kortty.model.TerminalRecordingScope;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

public class TerminalRecordingSession implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Path replayFile;
    private final String connectionName;
    private final boolean autoPauseEnabled;
    private final long idlePauseMillis;
    private final Clock clock;
    private final BufferedWriter writer;
    private final Map<String, TerminalRecordingScreenSnapshot> lastScreenByWidget = new HashMap<>();
    private final ScheduledExecutorService idleExecutor;
    private TerminalRecordingState state = TerminalRecordingState.IDLE;
    private Consumer<TerminalRecordingState> stateListener;
    private long lastActivityMillis;
    private int segmentCount;
    private boolean closed;

    TerminalRecordingSession(
        Path replayFile,
        String connectionName,
        boolean autoPauseEnabled,
        int idlePauseSeconds,
        Clock clock) throws IOException {
        this.replayFile = Objects.requireNonNull(replayFile, "replayFile must not be null").toAbsolutePath().normalize();
        this.connectionName = connectionName != null && !connectionName.isBlank() ? connectionName : "Terminal";
        this.autoPauseEnabled = autoPauseEnabled;
        this.idlePauseMillis = Math.max(1L, idlePauseSeconds) * 1000L;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Path parent = this.replayFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = openReplayWriter(this.replayFile);
        this.idleExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "TerminalRecordingIdle-" + TerminalRecordingService.sanitizeFileName(this.connectionName));
            thread.setDaemon(true);
            return thread;
        });
        JsonObject event = event("session_created");
        event.addProperty("connection", this.connectionName);
        event.addProperty("formatVersion", 1);
        writeEvent(event);
        idleExecutor.scheduleAtFixedRate(this::checkIdleQuietly, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void setStateListener(Consumer<TerminalRecordingState> stateListener) {
        this.stateListener = stateListener;
    }

    public synchronized void start(TerminalRecordingScope scope) throws IOException {
        ensureOpen();
        if (state == TerminalRecordingState.RECORDING || state == TerminalRecordingState.AUTO_PAUSED) {
            return;
        }
        segmentCount++;
        lastActivityMillis = clock.millis();
        setState(TerminalRecordingState.RECORDING);
        JsonObject event = event("recording_start");
        event.addProperty("scope", (scope != null ? scope : TerminalRecordingScope.ACTIVE_SPLIT).name());
        event.addProperty("segment", segmentCount);
        writeEvent(event);
    }

    public synchronized void stop() throws IOException {
        ensureOpen();
        if (state != TerminalRecordingState.RECORDING && state != TerminalRecordingState.AUTO_PAUSED) {
            return;
        }
        JsonObject event = event("recording_stop");
        event.addProperty("segment", segmentCount);
        writeEvent(event);
        setState(TerminalRecordingState.STOPPED);
    }

    public synchronized void recordScreenSnapshot(String widgetId, String content) {
        recordScreenSnapshot(widgetId, TerminalRecordingScreenSnapshot.plain(content));
    }

    public synchronized void recordScreenSnapshot(String widgetId, TerminalRecordingScreenSnapshot snapshot) {
        if (state != TerminalRecordingState.RECORDING && state != TerminalRecordingState.AUTO_PAUSED) {
            return;
        }
        String safeWidgetId = widgetId != null && !widgetId.isBlank() ? widgetId : "terminal";
        TerminalRecordingScreenSnapshot safeSnapshot = snapshot != null
            ? snapshot
            : TerminalRecordingScreenSnapshot.plain("");
        if (safeSnapshot.equals(lastScreenByWidget.get(safeWidgetId))) {
            return;
        }
        try {
            recordActivity("screen");
            lastScreenByWidget.put(safeWidgetId, safeSnapshot);
            JsonObject event = event("screen");
            event.addProperty("widget", safeWidgetId);
            event.addProperty("content", safeSnapshot.content());
            if (safeSnapshot.columns() > 0) {
                event.addProperty("columns", safeSnapshot.columns());
            }
            if (safeSnapshot.rows() > 0) {
                event.addProperty("rows", safeSnapshot.rows());
            }
            if (safeSnapshot.pixelWidth() > 0) {
                event.addProperty("pixelWidth", safeSnapshot.pixelWidth());
            }
            if (safeSnapshot.pixelHeight() > 0) {
                event.addProperty("pixelHeight", safeSnapshot.pixelHeight());
            }
            if (!safeSnapshot.styleRuns().isEmpty()) {
                JsonArray styleRuns = new JsonArray();
                for (TerminalRecordingStyleRun run : safeSnapshot.styleRuns()) {
                    JsonObject runJson = new JsonObject();
                    runJson.addProperty("row", run.row());
                    runJson.addProperty("column", run.column());
                    runJson.addProperty("text", run.text());
                    if (run.foreground() != null) {
                        runJson.addProperty("foreground", run.foreground());
                    }
                    if (run.background() != null) {
                        runJson.addProperty("background", run.background());
                    }
                    if (!run.options().isEmpty()) {
                        JsonArray options = new JsonArray();
                        run.options().forEach(options::add);
                        runJson.add("options", options);
                    }
                    styleRuns.add(runJson);
                }
                event.add("styleRuns", styleRuns);
            }
            writeEvent(event);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write terminal recording snapshot", e);
        }
    }

    public synchronized void recordUserInputActivity() {
        if (state != TerminalRecordingState.RECORDING && state != TerminalRecordingState.AUTO_PAUSED) {
            return;
        }
        try {
            recordActivity("user_input");
            writeEvent(event("user_input_activity"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write terminal recording activity", e);
        }
    }

    synchronized void checkIdle() throws IOException {
        if (!autoPauseEnabled || state != TerminalRecordingState.RECORDING) {
            return;
        }
        long idleMillis = clock.millis() - lastActivityMillis;
        if (idleMillis >= idlePauseMillis) {
            JsonObject event = event("auto_pause");
            event.addProperty("idleMillis", idleMillis);
            writeEvent(event);
            setState(TerminalRecordingState.AUTO_PAUSED);
        }
    }

    private void checkIdleQuietly() {
        try {
            checkIdle();
        } catch (IOException e) {
            throw new IllegalStateException("Could not write terminal recording idle event", e);
        }
    }

    private void recordActivity(String source) throws IOException {
        if (state == TerminalRecordingState.AUTO_PAUSED) {
            JsonObject event = event("auto_resume");
            event.addProperty("source", source);
            writeEvent(event);
            setState(TerminalRecordingState.RECORDING);
        }
        lastActivityMillis = clock.millis();
    }

    public synchronized Path getReplayFile() {
        return replayFile;
    }

    public synchronized TerminalRecordingState getState() {
        return state;
    }

    public synchronized boolean isActive() {
        return state == TerminalRecordingState.RECORDING || state == TerminalRecordingState.AUTO_PAUSED;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        if (isActive()) {
            stop();
        }
        writeEvent(event("session_closed"));
        closed = true;
        idleExecutor.shutdownNow();
        writer.close();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Terminal recording session is already closed");
        }
    }

    private void setState(TerminalRecordingState newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        Consumer<TerminalRecordingState> listener = stateListener;
        if (listener != null) {
            listener.accept(newState);
        }
    }

    private JsonObject event(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("at", Instant.now(clock).toString());
        return event;
    }

    private void writeEvent(JsonObject event) throws IOException {
        writer.write(GSON.toJson(event));
        writer.newLine();
        writer.flush();
    }

    private static BufferedWriter openReplayWriter(Path replayFile) throws IOException {
        OutputStream output = Files.newOutputStream(
            replayFile,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        try {
            OutputStream replayOutput = TerminalRecordingService.isCompressedReplayFile(replayFile)
                ? new GZIPOutputStream(output)
                : output;
            return new BufferedWriter(new OutputStreamWriter(replayOutput, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            try {
                output.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }
}
