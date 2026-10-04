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
 * <p>A shared (teamwork) connection was written by whoever edits the shared file, not on this
 * machine, so it cannot relax verification here: its own "don't verify" is ignored, and the group
 * exemptions, which belong to the folders of the local Connection Manager, do not apply to it even
 * where its group has the same name. Its own "verify" is honoured, and the global setting, which the
 * user chose for every connection, applies to it as well.
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
        // Enterprise policy: with enforce-host-key-check active, every scope (global, group,
        // per-connection) is overridden — checking can never be relaxed.
        if (de.kortty.policy.PolicyManager.effective().enforceHostKeyCheck()) {
            return HostKeyCheckMode.STRICT;
        }
        if (connection == null) {
            return HostKeyCheckMode.STRICT;
        }
        boolean local = !connection.isTeamworkConnection();
        Boolean perConnection = connection.getDisableHostKeyCheck();
        if (Boolean.FALSE.equals(perConnection)) {
            return HostKeyCheckMode.STRICT;
        }
        if (Boolean.TRUE.equals(perConnection) && local) {
            return HostKeyCheckMode.ACCEPT_NEW;
        }
        String group = connection.getGroup();
        if (local && group != null && !group.isBlank() && disabledGroups != null && disabledGroups.contains(group)) {
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
