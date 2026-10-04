package de.kortty.ui;

import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Objects;

/**
 * An OSC 8 link korTTY can open: a web or mail target that passed
 * {@link TerminalLinkOpener#allowedBrowseUri}, or a file a {@code file:} target names
 * ({@link TerminalFileLink#fromFileUri}). It keeps the validated target, so korTTY code can read it
 * back from a cell's {@code HyperlinkStyle}.
 *
 * <p>SithTermFX navigates a link only on a click its settings provider accepts as the open gesture,
 * which korTTY's provider limits to a single, still Cmd/Ctrl+click without Alt
 * ({@link TerminalLinkClickPolicy}). {@link #navigate()} hands the link to its pane's
 * {@link Follower}, which opens it the way every other link opens: after the host-mismatch question,
 * and a file only through the pane's file handler, read-only. The link itself holds no opener.
 */
public final class KorttyLinkInfo extends LinkInfo {

    /** Opens a link SithTermFX navigated; the pane's. Called on the JavaFX thread. */
    @FunctionalInterface
    public interface Follower {
        void follow(@NotNull KorttyLinkInfo link);
    }

    /** For a link in a buffer that no pane shows: navigating it does nothing. */
    static final Follower NO_PANE = link -> { };

    private final @Nullable URI target;
    private final @Nullable TerminalFileLink file;
    private final Follower follower;

    /** A web or mail link. */
    KorttyLinkInfo(@NotNull URI target, @NotNull Follower follower) {
        super(NOT_CALLED);
        this.target = Objects.requireNonNull(target, "target");
        this.file = null;
        this.follower = Objects.requireNonNull(follower, "follower");
    }

    /** A {@code file:} link. */
    KorttyLinkInfo(@NotNull TerminalFileLink file, @NotNull Follower follower) {
        super(NOT_CALLED);
        this.target = null;
        this.file = Objects.requireNonNull(file, "file");
        this.follower = Objects.requireNonNull(follower, "follower");
    }

    /** {@link LinkInfo} wants a callback; {@link #navigate()} is overridden, so it is never run. */
    private static final Runnable NOT_CALLED = () -> { };

    /** Hands the link to its pane, which decides whether and how it opens. */
    @Override
    public void navigate() {
        follower.follow(this);
    }

    /** The validated web or mail target, or {@code null} for a {@code file:} link. */
    public @Nullable URI target() {
        return target;
    }

    /** The file a {@code file:} link names, or {@code null} for a web or mail link. */
    public @Nullable TerminalFileLink file() {
        return file;
    }
}
