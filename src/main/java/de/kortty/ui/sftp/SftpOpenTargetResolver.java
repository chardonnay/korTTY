package de.kortty.ui.sftp;

import de.kortty.core.RemoteDirectoryChange;
import de.kortty.core.RemotePathSupport;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Where an SFTP manager opened from a terminal pane starts: in the folder the shell is in, or in
 * the login folder when that folder cannot be trusted. Pure and JavaFX-free; the terminal gathers
 * the inputs on the FX thread (the foreign-session verdict reads the screen) and hands them here.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>A foreign session (after {@code su}, {@code sudo -i} or a nested {@code ssh}) starts in the
 *       login folder: the shell's folder belongs to another user or host.</li>
 *   <li>A connection with a shell startup command starts in the login folder too: the command may
 *       switch user or host before the first prompt.</li>
 *   <li>An absolute directory reported by the shell (OSC 7, the agent hook) is used as it is.</li>
 *   <li>Else an absolute directory read from the prompt.</li>
 *   <li>Else an absolute directory from a typed {@code cd}; it may be stale, and the tab falls back
 *       to the login folder when it cannot list it.</li>
 *   <li>{@code ~}, {@code ~/x} and a relative path are resolved against the home folder
 *       ({@link RemotePathSupport#resolveTargetDirectory}); without a known home they stay as they
 *       are, and the tab resolves them against the SFTP login folder.</li>
 *   <li>Nothing usable: the login folder.</li>
 * </ol>
 */
public final class SftpOpenTargetResolver {

    /** Why the tab starts where it does. */
    public enum Reason {
        /** The directory the shell reported (OSC 7, agent hook or a typed {@code cd}). */
        TRACKED_DIRECTORY(null),
        /** The directory read from the visible prompt. */
        PROMPT_DIRECTORY(null),
        /** Nothing was known: the login folder. */
        LOGIN_DIRECTORY(null),
        /** A different session is active in the pane: the login folder, with a hint. */
        FOREIGN_SESSION("sftp.openHere.hint.foreign"),
        /** The connection runs a shell startup command: the login folder, with a hint. */
        STARTUP_COMMAND("sftp.openHere.hint.startupCommand");

        private final @Nullable String hintKey;

        Reason(@Nullable String hintKey) {
            this.hintKey = hintKey;
        }

        /** The i18n key of the status hint the tab shows, or null when there is nothing to say. */
        public @Nullable String hintKey() {
            return hintKey;
        }
    }

    /**
     * What the terminal knows about its pane.
     *
     * @param foreignSession                 the pane's foreign-session verdict (FX thread)
     * @param trackedDirectory               the connector's tracked directory, or null
     * @param trackedSource                  where it came from, or null while it is the initial {@code ~}
     * @param promptDirectory                the directory read from the prompt, or null
     * @param homeDirectory                  the login home as the connector knows it, or null
     * @param shellStartupCommandConfigured  whether the connection runs a startup command
     */
    public record Inputs(boolean foreignSession, @Nullable String trackedDirectory,
            RemoteDirectoryChange.@Nullable Source trackedSource, @Nullable String promptDirectory,
            @Nullable String homeDirectory, boolean shellStartupCommandConfigured) {
    }

    /**
     * Where to start.
     *
     * @param startPath the folder to list first, or null for the login folder
     * @param reason    why
     */
    public record OpenTarget(@Nullable String startPath, Reason reason) {
        public OpenTarget {
            Objects.requireNonNull(reason, "reason");
        }

        /** The i18n key of the status hint, or null. */
        public @Nullable String hintKey() {
            return reason.hintKey();
        }
    }

    private SftpOpenTargetResolver() {
    }

    /** Where an SFTP manager opened from the pane described by {@code inputs} starts. */
    public static OpenTarget resolve(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.foreignSession()) {
            return new OpenTarget(null, Reason.FOREIGN_SESSION);
        }
        if (inputs.shellStartupCommandConfigured()) {
            return new OpenTarget(null, Reason.STARTUP_COMMAND);
        }
        String tracked = trimmed(inputs.trackedDirectory());
        boolean confident = inputs.trackedSource() != null
            && inputs.trackedSource() != RemoteDirectoryChange.Source.TYPED_CD;
        if (confident && isAbsolute(tracked)) {
            return new OpenTarget(tracked, Reason.TRACKED_DIRECTORY);
        }
        String prompt = trimmed(inputs.promptDirectory());
        if (isAbsolute(prompt)) {
            return new OpenTarget(prompt, Reason.PROMPT_DIRECTORY);
        }
        if (isAbsolute(tracked)) {
            return new OpenTarget(tracked, Reason.TRACKED_DIRECTORY);
        }
        if (tracked == null) {
            return new OpenTarget(null, Reason.LOGIN_DIRECTORY);
        }
        String home = trimmed(inputs.homeDirectory());
        if (isAbsolute(home)) {
            String resolved = tracked.startsWith("~")
                ? RemotePathSupport.resolveTargetDirectory(tracked, home)
                : RemotePathSupport.appendRemotePath(home, tracked);
            return new OpenTarget(resolved, Reason.TRACKED_DIRECTORY);
        }
        if ("~".equals(tracked)) {
            return new OpenTarget(null, Reason.LOGIN_DIRECTORY);
        }
        // "~/x" or "x": the tab resolves it against the SFTP login folder, which is the home.
        return new OpenTarget(tracked, Reason.TRACKED_DIRECTORY);
    }

    private static boolean isAbsolute(@Nullable String path) {
        return path != null && path.startsWith("/");
    }

    private static @Nullable String trimmed(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
