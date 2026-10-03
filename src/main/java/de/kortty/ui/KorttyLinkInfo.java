package de.kortty.ui;

import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.Objects;

/**
 * An OSC 8 link whose target passed {@link TerminalLinkOpener#allowedBrowseUri}. It keeps the
 * validated target, so korTTY code can read it back from a cell's {@code HyperlinkStyle}.
 *
 * <p>{@link #navigate()} does nothing. SithTermFX calls it on every plain primary click over a link,
 * once per click of a double-click and after a drag-selection that ends on the link, and also while a
 * program has mouse reporting on. korTTY opens links only through {@link TerminalLinkClickPolicy}, on a
 * single Cmd/Ctrl+click: {@link TerminalLinkResolver} reads {@link #target()} from the clicked cell, and
 * the policy hands it to the {@link TerminalLinkOpener}.
 */
public final class KorttyLinkInfo extends LinkInfo {

    /** SithTermFX's own navigation; korTTY's click gate opens links instead. */
    private static final Runnable NO_NAVIGATION = () -> { };

    private final URI target;

    KorttyLinkInfo(@NotNull URI target) {
        super(NO_NAVIGATION);
        this.target = Objects.requireNonNull(target, "target");
    }

    /** The validated link target. */
    public @NotNull URI target() {
        return target;
    }
}
