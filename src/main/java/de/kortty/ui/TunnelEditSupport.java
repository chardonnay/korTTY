package de.kortty.ui;

import de.kortty.core.SshTunnelManager;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The SSH tunnel editing rules of the connection editor and the tunnel dialog, without JavaFX so
 * they are unit-testable: which label each field carries for a tunnel type, when to warn about a
 * listener other computers can reach, the text of the tunnel list, and the "Enable SSH tunnels"
 * switch over every tunnel of a connection.
 *
 * <p>The tunnel dialog keeps one model for all types (see {@link SshTunnelManager}): a LOCAL or
 * DYNAMIC tunnel listens on {@code localHost:localPort} here, a REMOTE tunnel makes the server
 * listen on {@code remoteHost:remotePort} and connects to {@code localHost:localPort} from here.
 * The labels say which of the two is the listener, so a remote tunnel's bind address is no longer
 * mistaken for a target host.
 */
final class TunnelEditSupport {

    /** The state of the "Enable SSH tunnels" switch: no tunnel enabled, some of them, or all. */
    enum SwitchState { NONE, SOME, ALL }

    /**
     * The I18n keys of the tunnel dialog's host and port fields for one tunnel type.
     *
     * @param remoteFirst whether the remote pair is shown first: it is the listener of a remote
     *                    tunnel, and the dialog lists the listener before the target
     * @param remoteUsed  false for a dynamic tunnel, which has no remote endpoint
     */
    record FieldLabels(
        String localHostKey,
        String localPortKey,
        String remoteHostKey,
        String remotePortKey,
        boolean remoteFirst,
        boolean remoteUsed) {
    }

    private TunnelEditSupport() {
    }

    private static TunnelType typeOrDefault(TunnelType type) {
        return type == null ? TunnelType.LOCAL : type;
    }

    /** The field labels for {@code type}; {@code null} counts as LOCAL like everywhere else. */
    static FieldLabels fieldLabels(TunnelType type) {
        return switch (typeOrDefault(type)) {
            case LOCAL -> new FieldLabels("tunnel.localBindAddress", "tunnel.localPort",
                "tunnel.remoteHost", "tunnel.remotePort", false, true);
            case REMOTE -> new FieldLabels("tunnel.localHost", "tunnel.localPort",
                "tunnel.remoteBindAddress", "tunnel.remotePort", true, true);
            case DYNAMIC -> new FieldLabels("tunnel.localBindAddress", "tunnel.localPort",
                "tunnel.remoteHost", "tunnel.remotePort", false, false);
        };
    }

    /** The I18n key of the type's name in the type selector. */
    static String typeLabelKey(TunnelType type) {
        return switch (typeOrDefault(type)) {
            case LOCAL -> "tunnel.type.local";
            case REMOTE -> "tunnel.type.remote";
            case DYNAMIC -> "tunnel.type.dynamic";
        };
    }

    /**
     * The I18n key of the warning for a listener that other computers may reach, or {@code null}
     * when the bind address keeps it private (blank means {@code localhost}). The bind address is
     * the local host of a LOCAL or DYNAMIC tunnel and the remote host of a REMOTE one; for the
     * latter the server only honours it when its {@code GatewayPorts} setting allows it.
     */
    static String bindWarningKey(TunnelType type, String localHost, String remoteHost) {
        if (typeOrDefault(type) == TunnelType.REMOTE) {
            return SshTunnelManager.isLoopbackBindHost(remoteHost) ? null : "tunnel.nonLoopbackWarning.remote";
        }
        return SshTunnelManager.isLoopbackBindHost(localHost) ? null : "tunnel.nonLoopbackWarning";
    }

    /**
     * One line of the connection editor's tunnel list: the enabled marker, the tunnel listener
     * first ({@code R localhost:9090 -> localhost:3000}), a note when other computers may reach
     * it, and the description.
     */
    static String listText(SSHTunnel tunnel) {
        if (tunnel == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(tunnel.isEnabled() ? "✓ " : "○ ")
            .append(TunnelStatusSupport.describe(tunnel));
        if (SshTunnelManager.plannedStatus(tunnel, false).exposed()) {
            text.append(" (").append(I18n.get("tunnel.state.exposed")).append(')');
        }
        String description = tunnel.getDescription();
        if (description != null && !description.isBlank()) {
            text.append(" - ").append(description.trim());
        }
        return text.toString();
    }

    /** Whether no tunnel, some or all of {@code tunnels} are enabled; an empty list is NONE. */
    static SwitchState switchState(List<SSHTunnel> tunnels) {
        long total = 0;
        long enabled = 0;
        for (SSHTunnel tunnel : tunnels == null ? List.<SSHTunnel>of() : tunnels) {
            if (tunnel == null) {
                continue;
            }
            total++;
            if (tunnel.isEnabled()) {
                enabled++;
            }
        }
        if (enabled == 0) {
            return SwitchState.NONE;
        }
        return enabled == total ? SwitchState.ALL : SwitchState.SOME;
    }

    /** Switches every tunnel on or off; what "Enable SSH tunnels" does when it is clicked. */
    static void setAllEnabled(List<SSHTunnel> tunnels, boolean enabled) {
        if (tunnels == null) {
            return;
        }
        for (SSHTunnel tunnel : tunnels) {
            if (tunnel != null) {
                tunnel.setEnabled(enabled);
            }
        }
    }

    /**
     * Copies of a connection's tunnels for the editor to work on, so that Cancel leaves the
     * connection's tunnels exactly as they were. Never null.
     */
    static List<SSHTunnel> workingCopies(List<SSHTunnel> tunnels) {
        List<SSHTunnel> copies = new ArrayList<>();
        for (SSHTunnel tunnel : tunnels == null ? List.<SSHTunnel>of() : tunnels) {
            if (tunnel != null) {
                copies.add(SshTunnelManager.copyOf(tunnel));
            }
        }
        return copies;
    }

    /**
     * Stores the edited tunnels on {@code connection} when the editor is saved. The connection's
     * list is updated in place: an open tab of the connection shares it (see
     * {@link ServerConnection#copyForAuth}) and reads it again when it applies the saved tunnels.
     */
    static void writeBack(ServerConnection connection, List<SSHTunnel> edited) {
        Objects.requireNonNull(connection, "connection");
        List<SSHTunnel> tunnels = edited == null ? List.of()
            : edited.stream().filter(Objects::nonNull).toList();
        List<SSHTunnel> target = connection.getSshTunnels();
        if (target == null) {
            connection.setSshTunnels(new ArrayList<>(tunnels));
            return;
        }
        try {
            target.clear();
            target.addAll(tunnels);
        } catch (UnsupportedOperationException e) {
            connection.setSshTunnels(new ArrayList<>(tunnels));
        }
    }
}
