package de.kortty.persistence;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * The schema version of files that store connections ({@code connections.xml}, korTTY exports,
 * teamwork files, the teamwork cache and recycle bin) and the one migration it gates.
 *
 * <p>Before version {@value #OWN_TERMINAL_SETTINGS_VERSION}, the connection editor stored a
 * connection's terminal values without ever switching it away from the global settings, so those
 * values never took effect. Files without a version (or with an older one) therefore load with every
 * connection on the global settings ({@code useGlobalSettings=true}): the stale values must not
 * suddenly change how existing connections look. Only an explicit choice of own settings in the
 * editor, saved in a file of the current version, makes a connection draw with its own values.</p>
 *
 * <p>Every write stamps {@link #CURRENT_VERSION}, so the migration applies to a file only until its
 * next save. It is idempotent: loading the same old file twice gives the same result.</p>
 */
public final class ConnectionSchemaMigration {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionSchemaMigration.class);

    /** The version from which {@code useGlobalSettings=false} means "this connection's own settings apply". */
    public static final int OWN_TERMINAL_SETTINGS_VERSION = 2;

    /** The version every write stamps. */
    public static final int CURRENT_VERSION = OWN_TERMINAL_SETTINGS_VERSION;

    private ConnectionSchemaMigration() {
    }

    /** Whether a file of {@code schemaVersion} ({@code null}: written before versions existed) needs the migration. */
    public static boolean needsMigration(Integer schemaVersion) {
        return schemaVersion == null || schemaVersion < OWN_TERMINAL_SETTINGS_VERSION;
    }

    /**
     * Brings connections read from a file of {@code schemaVersion} to the current schema: for an
     * older file, every connection keeps following the global terminal settings. Connections of a
     * current (or newer) file are left as they are.
     *
     * @return how many connections were switched back to the global settings
     */
    public static int migrate(Integer schemaVersion, Collection<ServerConnection> connections) {
        if (!needsMigration(schemaVersion) || connections == null) {
            return 0;
        }
        int switched = 0;
        for (ServerConnection connection : connections) {
            ConnectionSettings settings = connection != null ? connection.getSettings() : null;
            if (settings != null && !settings.isUseGlobalSettings()) {
                settings.setUseGlobalSettings(true);
                switched++;
            }
        }
        if (switched > 0) {
            logger.info("Connections file of schema version {}: {} connection(s) keep the global terminal settings",
                schemaVersion != null ? schemaVersion : "(none)", switched);
        }
        return switched;
    }
}
