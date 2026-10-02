package de.kortty.core;

import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The one-time confirmation in front of one terminal tab's SSH tunnels.
 *
 * <p>A tunnel set opens without a question only when the user already allowed exactly that set
 * for that connection ({@link SshTunnelApprovals}). Otherwise the user is asked once, listing only
 * the tunnels that would really listen: a set in which everything is refused anyway (a shared
 * connection's remote tunnel, an invalid port) needs no question, because nothing would open.
 * The answer holds for the tab, including its reconnects, even when it could not be stored; only
 * an approval is stored, so "Not Now" is asked again in a new tab.
 *
 * <p>Not thread-safe: the tab calls it on the JavaFX thread, where the question is shown.
 */
public final class SshTunnelApprovalGate {

    private final Supplier<SshTunnelApprovals> approvals;
    private String approvedHash;
    private String declinedHash;

    /** @param approvals the persisted approvals, resolved only once a question is due */
    public SshTunnelApprovalGate(Supplier<SshTunnelApprovals> approvals) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    /**
     * Whether {@code tunnels} may open for {@code connection} now.
     *
     * @param tunnels the enabled tunnels, as deep copies (see {@link SshTunnelManager#enabledTunnels})
     * @param ask     shows the question for the tunnels that would really open and returns true when
     *                the user allows them
     */
    public boolean allows(ServerConnection connection, List<SSHTunnel> tunnels, Predicate<List<SSHTunnel>> ask) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(ask, "ask");
        String hash = SshTunnelApprovals.tunnelSetHash(connection, tunnels);
        if (hash.equals(declinedHash)) {
            return false;
        }
        if (hash.equals(approvedHash)) {
            return true;
        }
        boolean shared = connection.isTeamworkConnection();
        List<SSHTunnel> startable = tunnels == null ? List.of()
            : tunnels.stream().filter(Objects::nonNull).filter(t -> SshTunnelManager.wouldStart(t, shared)).toList();
        if (startable.isEmpty()) {
            // Nothing would listen; the attach only records why each tunnel was refused.
            return true;
        }
        SshTunnelApprovals store = approvals.get();
        if (store.isApproved(connection.getId(), hash)) {
            approvedHash = hash;
            return true;
        }
        boolean allowed = ask.test(startable);
        if (allowed) {
            approvedHash = hash;
            store.approve(connection.getId(), hash);
        } else {
            declinedHash = hash;
        }
        return allowed;
    }
}
