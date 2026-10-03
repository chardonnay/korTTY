package de.kortty.ui;

import com.sithtermfx.core.model.hyperlinks.LinkInfoProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * korTTY's OSC 8 link provider, installed in every terminal pane in place of SithTermFX's default.
 *
 * <p>A program picks the target of an OSC 8 link ({@code ESC ] 8 ; ; target ST}) freely, so the
 * target is as untrusted as the rest of the output. SithTermFX's default provider also accepted
 * {@code file:} and {@code news:} targets and opened {@code file:} with {@code java.awt.Desktop.open},
 * which launches the file's associated application, executables included; on Windows a
 * {@code file:////host/share/x.exe} target even names a network share. This provider accepts only
 * what {@link TerminalLinkOpener#allowedBrowseUri} allows. For every other target it returns
 * {@code null}, so the text is drawn as plain text and a click does nothing.
 *
 * <p>The {@link KorttyLinkInfo} it returns does not open anything itself: links open only on a
 * Cmd/Ctrl+click, through {@link TerminalLinkClickPolicy}.
 *
 * <p>SithTermFX calls it on the emulator thread for every OSC 8 sequence, so it stays toolkit-free
 * and cheap.
 */
public final class KorttyOsc8LinkInfoProvider implements LinkInfoProvider {

    @Override
    public @Nullable KorttyLinkInfo createLinkInfo(@NotNull String uri) {
        return TerminalLinkOpener.allowedBrowseUri(uri)
            .map(KorttyLinkInfo::new)
            .orElse(null);
    }
}
