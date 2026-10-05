package de.kortty.isolation;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;

import java.util.function.Function;

/**
 * Works out which {@link IsolationLevel} a connection's terminal session asks for, and where that choice
 * comes from. Pure: no JavaFX.
 *
 * <p>For a connection of your own the most specific choice wins in both directions: the connection's
 * own level, else its folder's (or the nearest folder above), else Settings. A connection from a shared
 * teamwork file can only make it stricter: whoever maintains the shared file can isolate a server more,
 * but never undo isolation you chose for its folder or in Settings. The organization's minimum
 * ({@code [rule.isolation] minimum}) applies on top of all of it.
 */
public final class IsolationSettings {

    /** Where the level that applies comes from. */
    public enum Source {
        /** The connection's own choice. */
        CONNECTION,
        /** The connection's folder, or a folder above it ({@link Resolution#folderPath()}). */
        FOLDER,
        /** Settings → Security → Session isolation. */
        GLOBAL,
        /** The organization's policy raised it to its minimum. */
        POLICY
    }

    /**
     * The level that applies to a connection.
     *
     * @param level      the level asked for; never null
     * @param source     where it comes from
     * @param folderPath the folder that set it when {@code source} is {@link Source#FOLDER}, else null
     */
    public record Resolution(IsolationLevel level, Source source, String folderPath) {

        public Resolution {
            level = level != null ? level : IsolationLevel.DEFAULT;
            source = source != null ? source : Source.GLOBAL;
        }

        /** Whether the organization demands at least this level, so it must never run with less. */
        public boolean enforcedByPolicy() {
            return source == Source.POLICY;
        }
    }

    private IsolationSettings() {
    }

    /**
     * The level for {@code connection} from the global settings and the folder levels stored there.
     *
     * @param global     the global settings; null means {@link IsolationLevel#DEFAULT} and no folder levels
     * @param connection the connection; null resolves the global default
     * @param floor      the organization's minimum; null when the policy sets none
     */
    public static Resolution resolve(GlobalSettings global, ServerConnection connection, IsolationLevel floor) {
        IsolationLevel globalLevel = global != null ? global.getConnectionIsolationDefault() : IsolationLevel.DEFAULT;
        Function<String, IsolationLevel> folderLevels =
            global != null ? global::getConnectionGroupIsolationLevel : group -> null;
        return resolve(globalLevel, folderLevels, connection, floor);
    }

    /**
     * {@link #resolve(GlobalSettings, ServerConnection, IsolationLevel)} from its parts.
     *
     * @param globalLevel  the level of Settings; null means {@link IsolationLevel#DEFAULT}
     * @param levelOfGroup the stored level of a folder by its key, or null
     */
    public static Resolution resolve(IsolationLevel globalLevel, Function<String, IsolationLevel> levelOfGroup,
                                     ServerConnection connection, IsolationLevel floor) {
        IsolationLevel base = globalLevel != null ? globalLevel : IsolationLevel.DEFAULT;
        Resolution resolved = new Resolution(base, Source.GLOBAL, null);
        ConnectionGroupIsolation.Inherited folder = connection != null
            ? ConnectionGroupIsolation.inherited(connection.getGroup(), levelOfGroup) : null;
        IsolationLevel own = connection != null ? connection.getIsolationLevel() : null;

        if (connection != null && connection.isTeamworkConnection()) {
            if (folder != null && folder.level().ordinal() > resolved.level().ordinal()) {
                resolved = new Resolution(folder.level(), Source.FOLDER, folder.groupPath());
            }
            if (own != null && own.ordinal() > resolved.level().ordinal()) {
                resolved = new Resolution(own, Source.CONNECTION, null);
            }
        } else if (own != null) {
            resolved = new Resolution(own, Source.CONNECTION, null);
        } else if (folder != null) {
            resolved = new Resolution(folder.level(), Source.FOLDER, folder.groupPath());
        }

        if (floor != null && floor.ordinal() > resolved.level().ordinal()) {
            resolved = new Resolution(floor, Source.POLICY, null);
        }
        return resolved;
    }

    /**
     * The strongest level this version of korTTY can give a session of {@code protocol}: a local shell and
     * the native {@code mosh-client} are processes of their own already and can run in a sandbox; SSH runs
     * in a session worker process; the built-in Mosh client (mosh4j) runs inside korTTY and cannot be
     * isolated yet.
     *
     * @param sshWorkerAvailable  whether SSH sessions can run in a session worker process
     * @param sshSandboxAvailable whether such a worker can additionally run in a sandbox
     */
    public static IsolationLevel strongestSupported(ConnectionProtocol protocol, boolean sshWorkerAvailable,
                                                    boolean sshSandboxAvailable) {
        if (protocol == null) {
            return IsolationLevel.NONE;
        }
        return switch (protocol) {
            case LOCAL_SHELL, MOSH_CLIENT -> IsolationLevel.SANDBOX;
            case SSH_TCP -> sshSandboxAvailable && sshWorkerAvailable ? IsolationLevel.SANDBOX
                : sshWorkerAvailable ? IsolationLevel.PROCESS : IsolationLevel.NONE;
            case MOSH -> IsolationLevel.NONE;
        };
    }
}
