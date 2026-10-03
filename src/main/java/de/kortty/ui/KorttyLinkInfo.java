package de.kortty.ui;

import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.Objects;

/**
 * An OSC 8 link whose target passed {@link TerminalLinkOpener#allowedBrowseUri}. It keeps the
 * validated target, so korTTY code can read it back from a cell's {@code HyperlinkStyle}, and opens
 * it through the {@link TerminalLinkOpener} instead of SithTermFX's {@code java.awt.Desktop} handler.
 */
public final class KorttyLinkInfo extends LinkInfo {

    private final URI target;

    KorttyLinkInfo(@NotNull URI target, @NotNull TerminalLinkOpener opener) {
        super(() -> opener.open(target));
        this.target = Objects.requireNonNull(target, "target");
    }

    /** The validated link target. */
    public @NotNull URI target() {
        return target;
    }
}
