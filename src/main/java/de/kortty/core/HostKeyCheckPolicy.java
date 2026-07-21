package de.kortty.core;

import de.kortty.model.ServerConnection;

import java.util.Collection;
import java.util.Set;

/**
 * Resolves the effective {@link HostKeyCheckMode} for a connection from the three configurable
 * scopes, in strict precedence order:
 *
 * <ol>
 *   <li><b>Per-connection</b> — a tri-state override: verify, don't verify, or inherit. When set
 *       (not "inherit") it wins outright, so a single critical host can keep strict verification
 *       even if its group or the global setting turned it off.</li>
 *   <li><b>Per-group</b> — a group whose name is in the disabled set relaxes to accept-new, unless
 *       the connection overrode it above.</li>
 *   <li><b>Global</b> — the base default for every connection that inherits at both levels above.</li>
 * </ol>
 *
 * <p>Pure and free of JavaFX/SSHD so the precedence is unit-testable. A jump server's own host key
 * is never routed through this — it is always verified strictly (see {@link JumpHostSupport}).
 */
public final class HostKeyCheckPolicy {

    private HostKeyCheckPolicy() {
    }

    /**
     * The mode for {@code connection} given the global flag and the set of group names whose
     * checking is disabled. A {@code null} connection or unset scopes resolve to {@link
     * HostKeyCheckMode#STRICT}.
     */
    public static HostKeyCheckMode resolve(
            ServerConnection connection, boolean disabledForAllConnections, Collection<String> disabledGroups) {
        if (connection == null) {
            return HostKeyCheckMode.STRICT;
        }
        Boolean perConnection = connection.getDisableHostKeyCheck();
        if (perConnection != null) {
            return perConnection ? HostKeyCheckMode.ACCEPT_NEW : HostKeyCheckMode.STRICT;
        }
        String group = connection.getGroup();
        if (group != null && !group.isBlank() && disabledGroups != null && disabledGroups.contains(group)) {
            return HostKeyCheckMode.ACCEPT_NEW;
        }
        return disabledForAllConnections ? HostKeyCheckMode.ACCEPT_NEW : HostKeyCheckMode.STRICT;
    }

    /** Convenience overload accepting a {@link Set} for the disabled groups. */
    public static HostKeyCheckMode resolve(
            ServerConnection connection, boolean disabledForAllConnections, Set<String> disabledGroups) {
        return resolve(connection, disabledForAllConnections, (Collection<String>) disabledGroups);
    }

    /**
     * Resolves the mode from the live {@link de.kortty.model.GlobalSettings}. Falls back to STRICT if
     * settings are unavailable — the safe default, so a settings error never silently relaxes checking.
     */
    public static HostKeyCheckMode resolveFromSettings(ServerConnection connection) {
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null) {
                return HostKeyCheckMode.STRICT;
            }
            return resolve(connection,
                settings.isHostKeyCheckDisabledForAllConnections(),
                settings.getHostKeyCheckDisabledGroups());
        } catch (Exception e) {
            return HostKeyCheckMode.STRICT;
        }
    }
}
