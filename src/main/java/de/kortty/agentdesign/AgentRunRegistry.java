package de.kortty.agentdesign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent runs of one terminal pane and where each stands, for the designs that do not keep that
 * state in a panel of their own. Answers the same questions the classic panel does: how many runs wait
 * for input, work, are paused or are done, and which run the run controls act on. Not thread-safe; the
 * host uses it on the JavaFX thread.
 */
public final class AgentRunRegistry {

    /** Where a run stands. */
    public enum State {
        WORKING,
        PAUSED,
        AWAITING_INPUT,
        DONE
    }

    private static final class Run {
        private final String prompt;
        private final long startedAtMillis;
        private boolean finished;
        private boolean paused;
        private boolean awaitingInput;
        private long finishedAtMillis = -1L;

        private Run(String prompt, long startedAtMillis) {
            this.prompt = prompt;
            this.startedAtMillis = startedAtMillis;
        }
    }

    private final Map<String, Run> runs = new LinkedHashMap<>();

    public void begin(String runId, String prompt, long nowMillis) {
        if (runId != null) {
            runs.put(runId, new Run(prompt != null ? prompt : "", nowMillis));
        }
    }

    public void finish(String runId, long nowMillis) {
        Run run = runs.get(runId);
        if (run != null && !run.finished) {
            run.finished = true;
            run.paused = false;
            run.awaitingInput = false;
            run.finishedAtMillis = nowMillis;
        }
    }

    public void remove(String runId) {
        runs.remove(runId);
    }

    public void setPaused(String runId, boolean paused) {
        Run run = runs.get(runId);
        if (run != null && !run.finished) {
            run.paused = paused;
        }
    }

    public void setAwaitingInput(String runId, boolean awaitingInput) {
        Run run = runs.get(runId);
        if (run != null && !run.finished) {
            run.awaitingInput = awaitingInput;
        }
    }

    public boolean contains(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    public State state(String runId) {
        Run run = runs.get(runId);
        if (run == null || run.finished) {
            return State.DONE;
        }
        if (run.awaitingInput) {
            return State.AWAITING_INPUT;
        }
        return run.paused ? State.PAUSED : State.WORKING;
    }

    public String prompt(String runId) {
        Run run = runs.get(runId);
        return run != null ? run.prompt : "";
    }

    /** Milliseconds the run has been going, or went before it finished. */
    public long elapsedMillis(String runId, long nowMillis) {
        Run run = runs.get(runId);
        if (run == null) {
            return 0L;
        }
        long end = run.finished ? run.finishedAtMillis : nowMillis;
        return Math.max(0L, end - run.startedAtMillis);
    }

    /** Run ids in start order. */
    public List<String> runIds() {
        return new ArrayList<>(runs.keySet());
    }

    public boolean isEmpty() {
        return runs.isEmpty();
    }

    public boolean hasActiveRuns() {
        return runs.values().stream().anyMatch(run -> !run.finished);
    }

    /** Counts of runs by state: [awaitingInput, working, paused, done], as the classic panel reports them. */
    public int[] counts() {
        int[] counts = new int[4];
        for (String runId : runs.keySet()) {
            switch (state(runId)) {
                case AWAITING_INPUT -> counts[0]++;
                case WORKING -> counts[1]++;
                case PAUSED -> counts[2]++;
                case DONE -> counts[3]++;
            }
        }
        return counts;
    }

    /**
     * The run the run controls act on and a status bar shows: one waiting for input, else the newest
     * working one, else the newest unfinished one, else the newest one; {@code null} without runs.
     */
    public String focusRunId() {
        String awaiting = null;
        String working = null;
        String unfinished = null;
        String newest = null;
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            String runId = entry.getKey();
            State state = state(runId);
            if (state == State.AWAITING_INPUT && awaiting == null) {
                awaiting = runId;
            }
            if (state == State.WORKING) {
                working = runId;
            }
            if (state != State.DONE) {
                unfinished = runId;
            }
            newest = runId;
        }
        if (awaiting != null) {
            return awaiting;
        }
        if (working != null) {
            return working;
        }
        return unfinished != null ? unfinished : newest;
    }
}
