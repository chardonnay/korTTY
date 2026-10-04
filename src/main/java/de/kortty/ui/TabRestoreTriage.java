package de.kortty.ui;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides, without asking anything, whether a tab saved in a project can open right away when the
 * project is opened, or what it waits for. A project used to ask nothing and skip silently: a local
 * shell (whose authentication field says "password" by default) and every tab whose password was
 * not stored or sat in the locked vault simply did not come back. Now every saved tab is sorted
 * into one {@link Classification}; the window opens what is {@link Classification#READY} and lists
 * the rest in one bar (see {@link RestoreAttention}) instead of a dialog per tab.
 *
 * <p>The sign-in decision is {@link ConnectionAuthResolver}'s, non-interactive: it never shows a
 * dialog, never renews an expired temporary SSH key and checks the enterprise server policy before
 * anything else. A local file editor or image viewer tab needs no connection; it is ready while its
 * file exists. Toolkit-free, so it is unit-testable.
 */
final class TabRestoreTriage {

    /** The longest tab name the restore bar shows. */
    static final int MAX_LABEL_LENGTH = 80;

    /** What a saved tab needs before it can open. */
    enum Classification {
        /** Opens right away: a local shell, SSH key auth, a stored password, a valid temporary key, an existing local file. */
        READY,
        /** The stored password is in the master-password vault, which is locked; unlocking opens the tab. */
        NEEDS_UNLOCK,
        /** A password that is not stored, or a new temporary SSH key: only the user can give it (Connect…). */
        NEEDS_CREDENTIALS,
        /** The enterprise server policy blocks the server or its jump host. */
        BLOCKED,
        /** The saved connection or the local file no longer exists. */
        MISSING;

        /** The class of a {@link ConnectionAuthResolver} status: a password and a temporary key both need the user. */
        static Classification of(ConnectionAuthResolver.Status status) {
            return switch (Objects.requireNonNull(status, "status")) {
                case READY -> READY;
                case NEEDS_UNLOCK -> NEEDS_UNLOCK;
                case NEEDS_PASSWORD, NEEDS_TEMP_KEY -> NEEDS_CREDENTIALS;
                case BLOCKED -> BLOCKED;
                case MISSING -> MISSING;
            };
        }

        /** Whether the user can bring the tab back from the restore bar (Connect… or Unlock Vault…). */
        boolean waitsForUser() {
            return this == NEEDS_UNLOCK || this == NEEDS_CREDENTIALS;
        }
    }

    /** Why a tab did not open, as the restore bar names it. */
    enum Reason {
        PASSWORD("session.restore.reason.password"),
        TEMPORARY_KEY("session.restore.reason.temporaryKey"),
        VAULT_LOCKED("session.restore.reason.vaultLocked"),
        /** Shown with the blocked {@code host:port} as {@code {0}}. */
        BLOCKED("session.restore.reason.blocked"),
        CONNECTION_MISSING("session.restore.reason.connectionMissing"),
        FILE_MISSING("session.restore.reason.fileMissing");

        private final String i18nKey;

        Reason(String i18nKey) {
            this.i18nKey = i18nKey;
        }

        String i18nKey() {
            return i18nKey;
        }
    }

    /**
     * The decision for one saved tab.
     *
     * @param reason why it did not open; {@code null} for {@link Classification#READY}
     * @param auth   the sign-in for a tab that opens over a connection: for {@link Classification#READY}
     *               the connection, password and temporary key to open it with; {@code null} for a
     *               local file
     */
    record Outcome(Classification classification, @Nullable Reason reason,
                   @Nullable ConnectionAuthResolver.Resolution auth) {

        /** A local file editor or image viewer tab whose file exists. */
        static final Outcome LOCAL_FILE_READY = new Outcome(Classification.READY, null, null);

        /** A local file editor or image viewer tab whose file is gone. */
        static final Outcome LOCAL_FILE_MISSING = new Outcome(Classification.MISSING, Reason.FILE_MISSING, null);

        Outcome {
            Objects.requireNonNull(classification, "classification");
        }

        /** The outcome of a sign-in decision. */
        static Outcome of(ConnectionAuthResolver.Resolution auth) {
            Objects.requireNonNull(auth, "auth");
            Reason reason = switch (auth.status()) {
                case READY -> null;
                case NEEDS_PASSWORD -> Reason.PASSWORD;
                case NEEDS_TEMP_KEY -> Reason.TEMPORARY_KEY;
                case NEEDS_UNLOCK -> Reason.VAULT_LOCKED;
                case BLOCKED -> Reason.BLOCKED;
                case MISSING -> Reason.CONNECTION_MISSING;
            };
            return new Outcome(Classification.of(auth.status()), reason, auth);
        }

        boolean isReady() {
            return classification == Classification.READY;
        }

        /** The connection the tab opens over, as the sign-in found it; {@code null} for a local file or a missing connection. */
        @Nullable ServerConnection connection() {
            return auth != null ? auth.connection() : null;
        }

        /** The blocked {@code host:port} for {@link Classification#BLOCKED}, else {@code null}. */
        @Nullable String blockedTarget() {
            return auth != null ? auth.blockedTarget() : null;
        }

        /** Never prints a password or a key: the resolution hides them. */
        @Override
        public String toString() {
            return "Outcome[" + classification + (reason != null ? ", " + reason : "") + "]";
        }
    }

    private TabRestoreTriage() {
    }

    /** The saved type, with the default older files imply. */
    static SessionState.TabType tabType(SessionState state) {
        SessionState.TabType type = state.getTabType();
        return type != null ? type : SessionState.TabType.TERMINAL;
    }

    /**
     * Whether the saved tab opens over a connection: a terminal tab, an SFTP Manager tab, and a remote
     * file editor or image viewer tab. A project without Auto-Reconnect opens none of these.
     */
    static boolean opensOverConnection(SessionState state) {
        return switch (tabType(state)) {
            case TERMINAL, SFTP_MANAGER -> true;
            case FILE_EDITOR -> Boolean.TRUE.equals(state.getEditorIsRemote());
            case IMAGE_VIEWER -> Boolean.TRUE.equals(state.getImageIsRemote());
        };
    }

    /** The file of a file editor or image viewer tab, or {@code null} for other tabs and a tab without one. */
    static @Nullable String filePath(SessionState state) {
        return switch (tabType(state)) {
            case FILE_EDITOR -> state.getEditorFilePath();
            case IMAGE_VIEWER -> state.getImageFilePath();
            default -> null;
        };
    }

    /**
     * Sorts one saved tab, asking nothing.
     *
     * @param connectionOf    the saved connection the tab opens over, or {@code null} when there is none
     * @param resolver        the sign-in; only its non-interactive {@code resolve} is used
     * @param localFileExists whether a local file still exists
     * @return the outcome, or {@code null} for a file editor or image viewer tab that names no file —
     *     it has nothing to open
     */
    static @Nullable Outcome classify(SessionState state,
                                      Function<SessionState, ServerConnection> connectionOf,
                                      ConnectionAuthResolver resolver,
                                      Predicate<String> localFileExists) {
        Objects.requireNonNull(state, "state");
        SessionState.TabType type = tabType(state);
        if ((type == SessionState.TabType.FILE_EDITOR || type == SessionState.TabType.IMAGE_VIEWER)
                && filePath(state) == null) {
            return null;
        }
        if (!opensOverConnection(state)) {
            return localFileExists.test(filePath(state)) ? Outcome.LOCAL_FILE_READY : Outcome.LOCAL_FILE_MISSING;
        }
        return Outcome.of(resolver.resolve(connectionOf.apply(state), false));
    }

    /** Translations as {@link I18n#get(String, Object...)} gives them. */
    @FunctionalInterface
    interface Texts {
        String get(String key, Object... args);
    }

    /**
     * The name the restore bar gives a saved tab: a terminal tab's own name or its connection's name;
     * an SFTP Manager tab's connection; a file's name. Text from the project file is cleaned of
     * control and bidi characters and shortened, because a project can come from someone else.
     *
     * @param connection the tab's connection when it still exists
     * @param index      the tab's position in the saved window, from 0
     */
    static String label(SessionState state, @Nullable ServerConnection connection, int index, Texts texts) {
        String connectionName = connection != null ? clean(connection.getDisplayName()) : "";
        return switch (tabType(state)) {
            case TERMINAL -> {
                String title = clean(state.getTabTitle());
                if (!title.isEmpty()) {
                    yield title;
                }
                yield !connectionName.isEmpty() ? connectionName : texts.get("session.restore.tab.terminal", index + 1);
            }
            case SFTP_MANAGER -> !connectionName.isEmpty()
                ? texts.get("session.restore.tab.sftp", connectionName)
                : texts.get("session.restore.tab.sftpUnnamed", index + 1);
            case FILE_EDITOR, IMAGE_VIEWER -> {
                String name = clean(fileName(filePath(state)));
                yield !name.isEmpty() ? name : texts.get("session.restore.tab.terminal", index + 1);
            }
        };
    }

    /** The last segment of a local or remote path, with either separator. */
    static String fileName(@Nullable String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.strip();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
    }

    private static String clean(@Nullable String text) {
        return DisplayTextSanitizer.sanitize(text, MAX_LABEL_LENGTH);
    }
}
