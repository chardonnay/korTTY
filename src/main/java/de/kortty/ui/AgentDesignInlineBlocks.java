package de.kortty.ui;

import de.kortty.model.TerminalAgentModels;
import de.kortty.model.Theme;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KI-Agent design 03: each run shows up as a block in the terminal, right above the shell's prompt line.
 * The block lists the run's steps while it works, shows an open approval with its keys, and collapses
 * to the answer when the run ends; it then stays in the scrollback like any other output. FX thread.
 */
final class AgentDesignInlineBlocks {

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final Duration FRAME = Duration.millis(150);
    // Telling highlighting about korTTY's own lines scans the screen, so spinner frames do it at most this often.
    private static final long BASELINE_INTERVAL_MILLIS = 500L;
    private static final Duration REDUCED_FRAME = Duration.seconds(1);

    private static final class Block {
        private final AgentInlineBlockLayout.BlockState state;
        private AgentTerminalBlockWriter.Region region;
        private List<AgentInlineBlockLayout.Line> shown;
        private boolean journaled;

        private Block(AgentInlineBlockLayout.BlockState state) {
            this.state = state;
        }
    }

    private final AgentTerminalSurface surface;
    private final Map<String, Block> blocks = new LinkedHashMap<>();
    private final Timeline ticker = new Timeline();
    private AgentTerminalBlockWriter.Palette palette;
    private boolean reducedAnimations;
    private int frame;
    private long lastBaselineMillis;

    AgentDesignInlineBlocks(AgentTerminalSurface surface) {
        this.surface = surface;
        this.palette = paletteFor(null);
        ticker.setCycleCount(Animation.INDEFINITE);
        configureTicker();
    }

    void applyTheme(Theme theme) {
        palette = paletteFor(theme);
        for (Block block : blocks.values()) {
            block.shown = null;
        }
        renderAll();
    }

    void setReducedAnimations(boolean reducedAnimations) {
        if (this.reducedAnimations == reducedAnimations) {
            return;
        }
        this.reducedAnimations = reducedAnimations;
        boolean wasRunning = ticker.getStatus() == Animation.Status.RUNNING;
        ticker.stop();
        configureTicker();
        if (wasRunning) {
            ticker.play();
        }
    }

    void beginRun(String runId, String prompt) {
        blocks.put(runId, new Block(new AgentInlineBlockLayout.BlockState(prompt, System.currentTimeMillis())));
        render(runId);
        startTicker();
    }

    boolean hasRun(String runId) {
        return blocks.containsKey(runId);
    }

    void publishActivity(String runId, TerminalAgentModels.AgentActivity activity) {
        Block block = blocks.get(runId);
        if (block != null) {
            block.state.accept(activity, System.currentTimeMillis());
            render(runId);
        }
    }

    void setPaused(String runId, boolean paused) {
        Block block = blocks.get(runId);
        if (block != null) {
            block.state.paused = paused;
            render(runId);
        }
    }

    void showApproval(String runId, TerminalAgentModels.Approval approval) {
        Block block = blocks.get(runId);
        if (block != null) {
            block.state.pendingApproval = approval;
            block.state.pendingPassword = null;
            render(runId);
        }
    }

    void showPassword(String runId, TerminalAgentModels.PasswordRequest request) {
        Block block = blocks.get(runId);
        if (block != null) {
            block.state.pendingPassword = request;
            block.state.pendingApproval = null;
            render(runId);
        }
    }

    void clearPrompt(String runId) {
        Block block = blocks.get(runId);
        if (block != null) {
            block.state.pendingApproval = null;
            block.state.pendingPassword = null;
            // The service leaves the question row running; once answered it is done.
            for (Map.Entry<String, TerminalAgentModels.AgentActivity> entry : new ArrayList<>(block.state.rows.entrySet())) {
                TerminalAgentModels.AgentActivity row = entry.getValue();
                if (row.type() == TerminalAgentModels.AgentActivityType.QUESTION
                    && row.status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
                    block.state.rows.put(entry.getKey(), new TerminalAgentModels.AgentActivity(
                        row.id(), row.type(), TerminalAgentModels.AgentActivityStatus.COMPLETED, row.title(),
                        row.summary(), row.detail(), row.tokenUsage(), row.elapsedSeconds(), row.collapsible(),
                        row.collapsed(), row.actionCategory()));
                }
            }
            render(runId);
        }
    }

    void toggleDetails(String runId) {
        Block block = blocks.get(runId);
        if (block != null) {
            block.state.expanded = !block.state.expanded;
            render(runId);
        }
    }

    /** Remembers the run's answer for the collapsed block. Returns whether the run has a block. */
    boolean setFinalMessage(String runId, String message, TerminalAgentModels.Phase phase) {
        Block block = blocks.get(runId);
        if (block == null) {
            return false;
        }
        if (message != null && !message.isBlank()) {
            block.state.finalMessage = message;
        }
        if (phase != null) {
            block.state.finalPhase = phase;
        }
        return true;
    }

    void updatePhase(String runId, TerminalAgentModels.Phase phase) {
        Block block = blocks.get(runId);
        if (block != null && phase != null && isFinal(phase)) {
            block.state.finalPhase = phase;
        }
    }

    void finishRun(String runId) {
        Block block = blocks.get(runId);
        if (block == null || block.state.finished) {
            return;
        }
        block.state.finished = true;
        block.state.finishedAtMillis = System.currentTimeMillis();
        block.state.pendingApproval = null;
        block.state.pendingPassword = null;
        block.state.expanded = false;
        render(runId);
        stopTickerIfIdle();
    }

    /** Forgets a run whose block can no longer change (written in its final form, or abandoned). */
    void removeRun(String runId) {
        blocks.remove(runId);
        stopTickerIfIdle();
    }

    void dispose() {
        ticker.stop();
        blocks.clear();
    }

    private void renderAll() {
        for (String runId : new ArrayList<>(blocks.keySet())) {
            render(runId);
        }
    }

    private void render(String runId) {
        Block block = blocks.get(runId);
        AgentTerminalBlockWriter writer = surface != null ? surface.blockWriter() : null;
        if (block == null || writer == null) {
            return;
        }
        String spinner = reducedAnimations ? "…" : SPINNER[frame % SPINNER.length];
        List<AgentInlineBlockLayout.Line> lines =
            AgentInlineBlockLayout.render(block.state, writer.columns(), spinner, System.currentTimeMillis());
        if (lines.equals(block.shown)) {
            return;
        }
        boolean written;
        if (block.region == null) {
            block.region = writer.open(lines, palette);
            written = block.region != null;
        } else {
            AgentTerminalBlockWriter.Result result = writer.rewrite(block.region, lines, block.shown, palette);
            if (result == AgentTerminalBlockWriter.Result.LOST) {
                // The block scrolled away (or the screen was rebuilt): carry on in a new one above the prompt.
                block.region = writer.open(lines, palette);
                written = block.region != null;
            } else {
                written = result == AgentTerminalBlockWriter.Result.WRITTEN;
            }
        }
        if (!written) {
            return;
        }
        boolean structural = block.shown == null || block.shown.size() != lines.size() || block.state.finished;
        block.shown = lines;
        long now = System.currentTimeMillis();
        if (structural || now - lastBaselineMillis >= BASELINE_INTERVAL_MILLIS) {
            lastBaselineMillis = now;
            surface.localTextWritten();
        }
        if (block.state.finished && !block.journaled) {
            block.journaled = true;
            StringBuilder text = new StringBuilder("\r\n");
            for (AgentInlineBlockLayout.Line line : lines) {
                text.append(line.plain().stripTrailing()).append("\r\n");
            }
            surface.journalLocalOutput(text.toString());
            blocks.remove(runId);
        }
    }

    private void tick() {
        frame++;
        boolean pending = false;
        for (Map.Entry<String, Block> entry : new ArrayList<>(blocks.entrySet())) {
            Block block = entry.getValue();
            if (!block.state.finished || block.shown == null) {
                render(entry.getKey());
                pending = true;
            }
        }
        if (!pending) {
            ticker.stop();
        }
    }

    private void startTicker() {
        if (ticker.getStatus() != Animation.Status.RUNNING) {
            ticker.play();
        }
    }

    private void stopTickerIfIdle() {
        for (Block block : blocks.values()) {
            if (!block.state.finished || block.shown == null) {
                return;
            }
        }
        ticker.stop();
    }

    private void configureTicker() {
        ticker.getKeyFrames().setAll(new KeyFrame(reducedAnimations ? REDUCED_FRAME : FRAME, event -> tick()));
    }

    private AgentTerminalBlockWriter.Palette paletteFor(Theme theme) {
        ThemeCssSupport.AgentActivityColors colors = ThemeCssSupport.resolveAgentActivityColors(theme);
        boolean dark = surface == null || surface.darkBackground();
        return AgentTerminalBlockWriter.Palette.of(colors.accentColor(), colors.mutedTextColor(), colors.errorColor(), dark);
    }

    private static boolean isFinal(TerminalAgentModels.Phase phase) {
        return phase == TerminalAgentModels.Phase.DONE
            || phase == TerminalAgentModels.Phase.BLOCKED
            || phase == TerminalAgentModels.Phase.CANCELLED
            || phase == TerminalAgentModels.Phase.FAILED;
    }
}
