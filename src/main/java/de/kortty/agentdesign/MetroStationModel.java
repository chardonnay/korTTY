package de.kortty.agentdesign;

import de.kortty.model.TerminalAgentModels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The stations of the metro-line status bar (design 08) for one run: one per command, analysis and
 * question of the run, in the order they appeared, plus an end station that fills in when the run is
 * over. Activities are upserted by id, as the agent service re-publishes a step when it changes.
 */
public final class MetroStationModel {

    /** How a station is drawn. */
    public enum StationState {
        DONE,
        ACTIVE,
        ATTENTION,
        FAILED,
        CANCELLED,
        PENDING
    }

    /** What a station stands for, which picks its label. */
    public enum StationKind {
        COMMAND,
        ANALYSIS,
        QUESTION,
        END
    }

    /**
     * @param id     the activity id, or {@code "end"}
     * @param kind   what the station stands for
     * @param label  short label under the station (a command's first words), empty for kinds labelled
     *               by the UI
     * @param detail the full text for a tooltip
     * @param state  how it is drawn
     */
    public record Station(String id, StationKind kind, String label, String detail, StationState state) {
    }

    private static final int LABEL_MAX_CHARS = 14;

    private final Map<String, Station> stations = new LinkedHashMap<>();
    private TerminalAgentModels.Phase phase = TerminalAgentModels.Phase.STARTING;
    private boolean finished;

    public void accept(TerminalAgentModels.AgentActivity activity) {
        if (activity == null || activity.id() == null || activity.type() == null) {
            return;
        }
        StationKind kind = switch (activity.type()) {
            case ACTION -> StationKind.COMMAND;
            case THINKING -> StationKind.ANALYSIS;
            case QUESTION -> StationKind.QUESTION;
            case ERROR -> StationKind.COMMAND;
            case MESSAGE -> null;
        };
        if (kind == null) {
            return;
        }
        String label = kind == StationKind.COMMAND ? shortLabel(activity.title()) : "";
        String detail = firstNonBlank(activity.title(), activity.summary());
        stations.put(activity.id(), new Station(activity.id(), kind, label, detail, stateOf(activity)));
    }

    public void acceptPhase(TerminalAgentModels.Phase newPhase) {
        if (newPhase != null) {
            phase = newPhase;
        }
    }

    /** Marks the run over: a question still open is closed, an unfinished step is no longer active. */
    public void finish() {
        finished = true;
        stations.replaceAll((id, station) -> switch (station.state()) {
            case ACTIVE, ATTENTION -> new Station(id, station.kind(), station.label(), station.detail(),
                phase == TerminalAgentModels.Phase.CANCELLED ? StationState.CANCELLED : StationState.DONE);
            default -> station;
        });
    }

    /** A question was answered: its station is done. */
    public void questionAnswered() {
        stations.replaceAll((id, station) -> station.kind() == StationKind.QUESTION && station.state() == StationState.ATTENTION
            ? new Station(id, station.kind(), station.label(), station.detail(), StationState.DONE)
            : station);
    }

    /** The stations in order, ending with the end station. */
    public List<Station> stations() {
        List<Station> result = new ArrayList<>(stations.values());
        result.add(new Station("end", StationKind.END, "", "", endState()));
        return result;
    }

    /** Index of the station the moving light sits on: the active or attention one, else the last done one. */
    public int currentIndex() {
        List<Station> all = stations();
        for (int i = all.size() - 1; i >= 0; i--) {
            StationState state = all.get(i).state();
            if (state == StationState.ACTIVE || state == StationState.ATTENTION) {
                return i;
            }
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).state() != StationState.PENDING) {
                return i;
            }
        }
        return 0;
    }

    public boolean isFinished() {
        return finished;
    }

    private StationState endState() {
        if (!finished && !isFinalPhase(phase)) {
            return StationState.PENDING;
        }
        return switch (phase) {
            case FAILED, BLOCKED -> StationState.FAILED;
            case CANCELLED -> StationState.CANCELLED;
            default -> StationState.DONE;
        };
    }

    private static boolean isFinalPhase(TerminalAgentModels.Phase phase) {
        return phase == TerminalAgentModels.Phase.DONE
            || phase == TerminalAgentModels.Phase.BLOCKED
            || phase == TerminalAgentModels.Phase.CANCELLED
            || phase == TerminalAgentModels.Phase.FAILED;
    }

    private static StationState stateOf(TerminalAgentModels.AgentActivity activity) {
        if (activity.type() == TerminalAgentModels.AgentActivityType.ERROR
            || activity.status() == TerminalAgentModels.AgentActivityStatus.FAILED) {
            return StationState.FAILED;
        }
        if (activity.status() == TerminalAgentModels.AgentActivityStatus.CANCELLED) {
            return StationState.CANCELLED;
        }
        if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
            return activity.type() == TerminalAgentModels.AgentActivityType.QUESTION
                ? StationState.ATTENTION
                : StationState.ACTIVE;
        }
        return StationState.DONE;
    }

    /** The first one or two words of a command, cut to fit under a station. */
    static String shortLabel(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String[] words = command.strip().split("\\s+");
        String first = words[0];
        if ("sudo".equals(first.toLowerCase(Locale.ROOT)) && words.length > 1) {
            first = words[1];
        }
        String label = first;
        int index = "sudo".equalsIgnoreCase(words[0]) ? 2 : 1;
        if (index < words.length && words[index].startsWith("-") && label.length() + 1 + words[index].length() <= LABEL_MAX_CHARS) {
            label = label + " " + words[index];
        }
        if (label.length() > LABEL_MAX_CHARS) {
            label = label.substring(0, LABEL_MAX_CHARS - 1) + "…";
        }
        return label;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second != null ? second.strip() : "";
    }
}
