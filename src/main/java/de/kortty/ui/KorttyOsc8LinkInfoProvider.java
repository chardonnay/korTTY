package de.kortty.ui;

import com.sithtermfx.core.model.hyperlinks.LinkInfoProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * korTTY's OSC 8 link provider, installed in every terminal pane in place of SithTermFX's default.
 *
 * <p>A program picks the target of an OSC 8 link ({@code ESC ] 8 ; ; target ST}) freely, so the
 * target is as untrusted as the rest of the output. SithTermFX's default provider also accepted
 * {@code file:} and {@code news:} targets and opened {@code file:} with {@code java.awt.Desktop.open},
 * which launches the file's associated application, executables included; on Windows a
 * {@code file:////host/share/x.exe} target even names a network share. This provider accepts what
 * {@link TerminalLinkOpener#allowedBrowseUri} allows, and a {@code file:} target only in a pane
 * that opens files ({@code fileLinks}) and only as {@link TerminalFileLink#fromFileUri} reads it:
 * such a file is read as text into the Snippet Editor, never launched. For every other target it
 * returns {@code null}, so the text is drawn as plain text and a click does nothing.
 *
 * <p>The {@link KorttyLinkInfo} it returns does not open anything itself: SithTermFX navigates it
 * only on korTTY's open gesture, a single, still Cmd/Ctrl+click ({@link TerminalLinkClickPolicy}),
 * and navigating hands it to the pane's {@link KorttyLinkInfo.Follower}.
 *
 * <p>SithTermFX calls it on the emulator thread for every OSC 8 sequence, so it stays toolkit-free
 * and cheap.
 */
public final class KorttyOsc8LinkInfoProvider implements LinkInfoProvider {

    private final BooleanSupplier fileLinks;
    private final KorttyLinkInfo.Follower follower;

    /** A provider for a buffer no pane shows: it keeps {@code file:} targets as plain text. */
    public KorttyOsc8LinkInfoProvider() {
        this(() -> false);
    }

    /**
     * A provider for a buffer no pane shows: navigating its links does nothing.
     *
     * @param fileLinks whether {@code file:} targets become links now
     */
    public KorttyOsc8LinkInfoProvider(@NotNull BooleanSupplier fileLinks) {
        this(fileLinks, KorttyLinkInfo.NO_PANE);
    }

    /**
     * @param fileLinks whether the pane opens files now; asked on the emulator thread, for each
     *     {@code file:} target only
     * @param follower  the pane's, given every link SithTermFX navigates
     */
    public KorttyOsc8LinkInfoProvider(@NotNull BooleanSupplier fileLinks, @NotNull KorttyLinkInfo.Follower follower) {
        this.fileLinks = Objects.requireNonNull(fileLinks, "fileLinks");
        this.follower = Objects.requireNonNull(follower, "follower");
    }

    @Override
    public @Nullable KorttyLinkInfo createLinkInfo(@NotNull String uri) {
        KorttyLinkInfo web = TerminalLinkOpener.allowedBrowseUri(uri)
            .map(target -> new KorttyLinkInfo(target, follower))
            .orElse(null);
        if (web != null || !uri.regionMatches(true, 0, "file:", 0, "file:".length()) || !fileLinks.getAsBoolean()) {
            return web;
        }
        return TerminalFileLink.fromFileUri(uri)
            .map(file -> new KorttyLinkInfo(file, follower))
            .orElse(null);
    }
}
