package de.kortty.ui;

import de.kortty.core.SshTunnelManager;
import de.kortty.core.SshTunnelManager.Failure;
import de.kortty.core.SshTunnelManager.State;
import de.kortty.core.SshTunnelManager.TunnelStatus;
import de.kortty.model.SSHTunnel;

import java.util.ArrayList;
import java.util.List;

/**
 * Text for a tab's SSH tunnels: the status-bar segment, its tooltip and the one-time approval
 * question. Pure helpers without JavaFX, so the wording is unit-testable, like
 * {@link TerminalDisconnectSupport}.
 *
 * <p>A tunnel is written the way {@code ssh} options read, listener first:
 * {@code L localhost:8080 -> db:5432}, {@code R localhost:9090 -> localhost:3000} (the server
 * listens, this computer is the target) and {@code D localhost:1080 (SOCKS)}.
 */
final class TunnelStatusSupport {

    private static final String SEPARATOR = " | ";

    private TunnelStatusSupport() {
    }

    /** The tunnel as configured, with the defaults the tunnel manager applies. */
    static String describe(SSHTunnel tunnel) {
        if (tunnel == null) {
            return "";
        }
        TunnelStatus status = statusOf(tunnel);
        return describe(status);
    }

    /** The tunnel behind a status, listener first. */
    static String describe(TunnelStatus status) {
        if (status == null) {
            return "";
        }
        String bind = hostPort(status.bindHost(), status.bindPort());
        return switch (status.type()) {
            case LOCAL -> "L " + bind + " -> " + hostPort(status.targetHost(), status.targetPort());
            case REMOTE -> "R " + bind + " -> " + hostPort(status.targetHost(), status.targetPort());
            case DYNAMIC -> "D " + bind + " (SOCKS)";
        };
    }

    /**
     * The status-bar segment for a tab's tunnels, or {@code null} when it has none. Either one
     * sentence explaining why the whole set was not started, or the active count followed by the
     * first failure and the first listener that other computers may reach.
     */
    static String statusBarSummary(List<TunnelStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        if (statuses.stream().allMatch(s -> s.state() == State.NOT_STARTED)) {
            return notStartedSummary(statuses.get(0).failure());
        }
        long active = statuses.stream().filter(s -> s.state() == State.ACTIVE).count();
        StringBuilder summary = new StringBuilder(I18n.get("statusBar.tunnels", active, statuses.size()));
        statuses.stream()
            .filter(s -> s.state() == State.FAILED)
            .findFirst()
            .ifPresent(failed -> summary.append(SEPARATOR)
                .append(I18n.get("statusBar.tunnelFailed", describe(failed), failureLabel(failed))));
        statuses.stream()
            .filter(s -> s.state() == State.ACTIVE && s.exposed())
            .findFirst()
            .ifPresent(exposed -> summary.append(SEPARATOR)
                .append(I18n.get("statusBar.tunnelExposed", describe(exposed))));
        return summary.toString();
    }

    private static String notStartedSummary(Failure reason) {
        return switch (reason) {
            case POLICY_DENIED -> I18n.get("statusBar.tunnelsPolicyDenied");
            case NOT_CONFIRMED -> I18n.get("statusBar.tunnelsNotConfirmed");
            default -> I18n.get("statusBar.tunnelsUnsupported");
        };
    }

    /** One line per tunnel with its state, or {@code null} when there are no tunnels. */
    static String tooltip(List<TunnelStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (TunnelStatus status : statuses) {
            StringBuilder line = new StringBuilder(describe(status));
            if (status.description() != null && !status.description().isBlank()) {
                line.append(" (").append(status.description()).append(')');
            }
            line.append(": ").append(stateLabel(status));
            if (status.state() == State.ACTIVE && status.exposed()) {
                line.append(" - ").append(I18n.get("tunnel.state.exposed"));
            }
            lines.add(line.toString());
        }
        if (statuses.stream().anyMatch(s -> s.state() == State.NOT_STARTED
                && s.failure() == Failure.NOT_CONFIRMED)) {
            lines.add(I18n.get("tunnel.notConfirmedHint"));
        }
        return String.join("\n", lines);
    }

    /** The localized state of one tunnel, e.g. "active on localhost:8080" or "failed: ...". */
    static String stateLabel(TunnelStatus status) {
        return switch (status.state()) {
            case STARTING -> I18n.get("tunnel.state.starting");
            case ACTIVE -> I18n.get("tunnel.state.active",
                status.boundAddress() != null ? status.boundAddress() : hostPort(status.bindHost(), status.bindPort()));
            case FAILED -> I18n.get("tunnel.state.failed", failureLabel(status));
            case STOPPED -> I18n.get("tunnel.state.stopped");
            case NOT_STARTED -> I18n.get("tunnel.state.notStarted", failureLabel(status));
        };
    }

    /** Why a tunnel failed or was not started, for the user. */
    static String failureLabel(TunnelStatus status) {
        String detail = status.detail();
        return switch (status.failure()) {
            case INVALID_PORT -> I18n.get("tunnel.error.invalidPort");
            case SHARED_NON_LOOPBACK -> I18n.get("tunnel.error.sharedNonLoopback");
            case SHARED_REMOTE -> I18n.get("tunnel.error.sharedRemote");
            // The operating system's own words ("Address already in use") are the useful part.
            case BIND_FAILED -> detail != null && !detail.isBlank() ? detail : I18n.get("tunnel.error.bindFailed");
            case SERVER_REJECTED -> I18n.get("tunnel.error.serverRejected");
            case POLICY_DENIED -> I18n.get("tunnel.error.policyDenied");
            case NOT_CONFIRMED -> I18n.get("tunnel.error.notConfirmed");
            case UNSUPPORTED_PROTOCOL -> I18n.get("tunnel.error.unsupported");
            case NONE -> "";
        };
    }

    /**
     * The body of the one-time question before a connection's tunnels open: what will listen
     * where, flagged when other computers may reach it, plus a note for shared connections.
     *
     * @param tunnels the tunnels that would actually open (refused ones are reported in the
     *                status bar instead)
     */
    static String approvalText(String connectionName, List<SSHTunnel> tunnels, boolean sharedConnection) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(I18n.get("tunnel.approval.body", connectionName == null ? "" : connectionName));
        List<String> lines = new ArrayList<>();
        for (SSHTunnel tunnel : tunnels) {
            TunnelStatus status = statusOf(tunnel);
            StringBuilder line = new StringBuilder("  ").append(describe(status));
            if (status.description() != null && !status.description().isBlank()) {
                line.append(" (").append(status.description()).append(')');
            }
            if (status.exposed()) {
                line.append(" - ").append(I18n.get("tunnel.state.exposed"));
            }
            lines.add(line.toString());
        }
        paragraphs.add(String.join("\n", lines));
        if (sharedConnection) {
            paragraphs.add(I18n.get("tunnel.approval.shared"));
        }
        paragraphs.add(I18n.get("tunnel.approval.note"));
        return String.join("\n\n", paragraphs);
    }

    private static TunnelStatus statusOf(SSHTunnel tunnel) {
        return SshTunnelManager.plannedStatus(tunnel, false);
    }

    private static String hostPort(String host, int port) {
        String safeHost = host == null ? "" : host;
        if (safeHost.indexOf(':') >= 0 && !safeHost.startsWith("[")) {
            safeHost = "[" + safeHost + "]";
        }
        return safeHost + ":" + port;
    }
}
