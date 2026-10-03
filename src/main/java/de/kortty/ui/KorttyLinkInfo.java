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
 * <p>{@link #navigate()} does nothing. SithTermFX calls it on every plain primary click over a link,
 * once per click of a double-click and after a drag-selection that ends on the link, and also while a
 * program has mouse reporting on. korTTY opens links only through {@link TerminalLinkClickPolicy}, on a
 * single Cmd/Ctrl+click: {@link TerminalLinkResolver} reads {@link #target()} or {@link #file()} from
 * the clicked cell, and the policy hands it to the {@link TerminalLinkOpener} or the pane's file
 * handler.
 */
public final class KorttyLinkInfo extends LinkInfo {

    /** SithTermFX's own navigation; korTTY's click gate opens links instead. */
    private static final Runnable NO_NAVIGATION = () -> { };

    private final @Nullable URI target;
    private final @Nullable TerminalFileLink file;

    /** A web or mail link. */
    KorttyLinkInfo(@NotNull URI target) {
        super(NO_NAVIGATION);
        this.target = Objects.requireNonNull(target, "target");
        this.file = null;
    }

    /** A {@code file:} link. */
    KorttyLinkInfo(@NotNull TerminalFileLink file) {
        super(NO_NAVIGATION);
        this.target = null;
        this.file = Objects.requireNonNull(file, "file");
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
