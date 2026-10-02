package de.kortty.ui;

import de.kortty.model.ServerConnection;
import javafx.scene.control.Tab;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Reopening the SFTP tabs of a saved project: which connection a saved SFTP Manager tab belongs
 * to, which folders it starts in, and closing the SFTP session a restored remote image or editor
 * tab opened for itself together with that tab.
 */
final class SftpSessionRestoreSupport {

    private static final Logger logger = LoggerFactory.getLogger(SftpSessionRestoreSupport.class);

    /** The remote folder a tab starts in without a saved one: the login directory. */
    static final String REMOTE_HOME = "~";

    /** Tab property holding the close action of the SFTP session the tab owns. */
    private static final Object OWNED_SESSION_KEY = new Object();

    private SftpSessionRestoreSupport() {
    }

    /**
     * The connection a saved SFTP Manager tab belongs to. Tabs are saved with the connection's id.
     * Projects saved before that stored the connection's name instead, so when no connection has
     * {@code savedId} as its id, a connection whose name is exactly {@code savedId} is used — but
     * only if it is the only one with that name.
     *
     * @param savedId the saved connection reference (an id, or a name in older projects)
     * @param byId looks a connection up by its id
     * @param all every connection, for the name fallback
     * @return the connection, or {@code null} when none or several match
     */
    static ServerConnection findConnection(
            String savedId,
            Function<String, ServerConnection> byId,
            Collection<ServerConnection> all) {
        if (savedId == null || savedId.isBlank()) {
            return null;
        }
        ServerConnection connection = byId.apply(savedId);
        if (connection != null) {
            return connection;
        }
        List<ServerConnection> named = all == null ? List.of() : all.stream()
            .filter(Objects::nonNull)
            .filter(candidate -> savedId.equals(candidate.getName()))
            .toList();
        if (named.size() == 1) {
            logger.info("SFTP tab saved with the connection name '{}' (older project); restoring it for that connection",
                savedId);
            return named.get(0);
        }
        if (named.isEmpty()) {
            logger.warn("SFTP tab not restored: no connection has the id or name '{}'", savedId);
        } else {
            logger.warn("SFTP tab not restored: {} connections are named '{}'", named.size(), savedId);
        }
        return null;
    }

    /**
     * The local folder a restored tab starts in: the saved one while it is still an existing
     * folder given as an absolute path, otherwise {@code home}.
     */
    static Path initialLocalPath(String saved, Path home) {
        if (saved == null || saved.isBlank()) {
            return home;
        }
        try {
            Path path = Path.of(saved.trim());
            if (path.isAbsolute() && Files.isDirectory(path)) {
                return path;
            }
        } catch (InvalidPathException e) {
            logger.debug("Saved local SFTP folder is not a valid path: {}", e.getMessage());
        }
        return home;
    }

    /** The remote folder a restored tab starts in: the saved one, or {@link #REMOTE_HOME} if none was saved. */
    static String initialRemotePath(String saved) {
        return saved == null || saved.isBlank() ? REMOTE_HOME : saved.trim();
    }

    /**
     * Whether a failed listing means the folder is not there (any more): no such file or path, or
     * not a folder. OpenSSH reports a path that became a file as "no such file" as well. The SFTP
     * status arrives wrapped — in the listing future's {@link CompletionException}, and in the
     * {@link UncheckedIOException} the directory iterator throws.
     */
    static boolean isMissingFolder(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (cause instanceof SftpException sftpException) {
                int status = sftpException.getStatus();
                return status == SftpConstants.SSH_FX_NO_SUCH_FILE
                    || status == SftpConstants.SSH_FX_NO_SUCH_PATH
                    || status == SftpConstants.SSH_FX_NOT_A_DIRECTORY;
            }
            if (!(cause instanceof CompletionException
                    || cause instanceof ExecutionException
                    || cause instanceof UncheckedIOException)) {
                return false;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Makes {@code tab} the owner of an SFTP session: {@code closeSession} runs once, when the tab
     * is closed with its close button or by {@link #closeOwnedSession(Tab)} on the programmatic
     * close paths. The action is kept in the tab's own properties, so it follows the tab when the
     * tab is dragged into another window.
     *
     * <p>An event handler is added rather than {@code onClosed} replaced, because
     * {@link FileEditorTab} disposes its editor there. JavaFX fires {@link Tab#CLOSED_EVENT} from
     * the close button only for a tab that has an {@code onClosed} handler, so a tab without one,
     * such as {@link ImageViewerTab}, gets an empty one.
     */
    static void closeWithTab(Tab tab, Runnable closeSession) {
        Objects.requireNonNull(tab, "tab");
        Objects.requireNonNull(closeSession, "closeSession");
        tab.getProperties().put(OWNED_SESSION_KEY, closeSession);
        tab.addEventHandler(Tab.CLOSED_EVENT, event -> closeOwnedSession(tab));
        if (tab.getOnClosed() == null) {
            tab.setOnClosed(event -> { });
        }
    }

    /** Closes the SFTP session {@code tab} owns, if any; does nothing the second time. */
    static void closeOwnedSession(Tab tab) {
        if (tab == null) {
            return;
        }
        if (tab.getProperties().remove(OWNED_SESSION_KEY) instanceof Runnable closeSession) {
            closeSession.run();
        }
    }
}
