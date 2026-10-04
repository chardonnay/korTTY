package de.kortty.ui;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.KorttyClipboard;
import de.kortty.model.GlobalSettings;
import de.kortty.shellintegration.Osc52Support;
import de.kortty.shellintegration.Osc52Support.Decoded;
import de.kortty.shellintegration.Osc52Support.Rejection;
import de.kortty.shellintegration.ShellIntegrationEvent.ClipboardWrite;
import javafx.scene.control.TabPane;
import javafx.stage.Window;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Carries out what a program in a terminal pane asks for with {@code OSC 52}: put text on the
 * clipboard, as vim, Neovim and tmux do when you copy in them on a server.
 *
 * <p>Only with {@code GlobalSettings.osc52ClipboardWriteEnabled} on, which is off by default: any
 * program whose output reaches the terminal could otherwise replace what the user copied, ready for
 * the next paste. The text goes through {@link KorttyClipboard}, so the enterprise policy's internal
 * clipboard mode keeps it inside korTTY. Writing is all a program can do; korTTY never answers an
 * {@code OSC 52} query, so the clipboard is never read back to the program.
 *
 * <p>The status bar of the tab's window says what happened and names the tab, so a change of the
 * clipboard never goes unnoticed, and also says when the setting stopped a write. korTTY's log
 * records the size of a write, never its text. JavaFX thread.
 */
public final class TerminalClipboardWriter {

    private static final Logger logger = LoggerFactory.getLogger(TerminalClipboardWriter.class);

    /** At most this many characters of the tab's name go into the status message. */
    static final int MAX_TAB_NAME_CHARS = 80;

    private static @Nullable TerminalClipboardWriter shared;

    /** What {@link #write} did. */
    enum Result {
        /** The text is on the clipboard. */
        WRITTEN,
        /** The setting does not allow programs to write the clipboard. */
        BLOCKED,
        /** The data is not base64 of UTF-8 text, or it is larger than the cap. */
        REJECTED,
        /** An empty write: nothing to say. */
        IGNORED
    }

    /**
     * What {@link #write} did with one write.
     *
     * @param characters how many characters went on the clipboard; 0 unless written
     * @param bytes      how many UTF-8 bytes they have; 0 unless written
     * @param rejection  why the data was not used; {@code null} unless rejected or ignored
     */
    record Outcome(Result result, int characters, int bytes, @Nullable Rejection rejection) {

        static Outcome written(String text, int bytes) {
            return new Outcome(Result.WRITTEN, text.codePointCount(0, text.length()), bytes, null);
        }

        static Outcome blocked() {
            return new Outcome(Result.BLOCKED, 0, 0, null);
        }

        static Outcome refused(Rejection rejection) {
            return new Outcome(rejection == Rejection.EMPTY ? Result.IGNORED : Result.REJECTED, 0, 0, rejection);
        }
    }

    private final Supplier<GlobalSettings> settings;

    private final Consumer<String> clipboard;

    private final BiConsumer<TerminalTab, String> status;

    /**
     * @param settings  the current settings; {@code null} counts as the defaults, where writing is off
     * @param clipboard where the text goes, {@link KorttyClipboard#setText}
     * @param status    shows a message in the status bar of the tab's window
     */
    TerminalClipboardWriter(Supplier<GlobalSettings> settings, Consumer<String> clipboard,
            BiConsumer<TerminalTab, String> status) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.status = Objects.requireNonNull(status, "status");
    }

    /** The writer of the running application, for every window. JavaFX thread. */
    static TerminalClipboardWriter shared() {
        TerminalClipboardWriter writer = shared;
        if (writer == null) {
            writer = new TerminalClipboardWriter(TerminalAttentionNotifier::currentSettings, KorttyClipboard::setText,
                TerminalClipboardWriter::showInWindow);
            shared = writer;
        }
        return writer;
    }

    /**
     * A program in a pane of {@code tab} asked to put text on the clipboard with {@code OSC 52}. With
     * the setting on, valid text of at most 256 KiB goes on the clipboard; the status bar of the
     * tab's window says what happened either way. JavaFX thread.
     */
    public void onClipboardWrite(TerminalTab tab, ClipboardWrite write) {
        if (tab == null || write == null) {
            return;
        }
        GlobalSettings current = settings.get();
        Outcome outcome = write(write, allowed(current, de.kortty.policy.PolicyManager.effective()), clipboard);
        switch (outcome.result()) {
            case WRITTEN -> logger.info("A program in a terminal pane put {} bytes on the clipboard (OSC 52)",
                outcome.bytes());
            case BLOCKED -> logger.debug("OSC 52 clipboard write of {} chars not allowed by the settings",
                write.data().length());
            case REJECTED, IGNORED -> logger.debug("OSC 52 clipboard write of {} chars ignored: {}",
                write.data().length(), outcome.rejection());
        }
        String message = statusText(outcome, tab.getEffectiveTitle(), I18n::get);
        if (message != null) {
            status.accept(tab, message);
        }
    }

    /**
     * Whether a program may put text on the clipboard now: the setting in Settings → Terminal is on and
     * the organization's policy does not forbid it ({@code allow-osc52-clipboard-write = false}). The
     * policy is asked here as well as clamped into the settings, so a hand-edited settings file between
     * two loads cannot let a write through.
     */
    static boolean allowed(GlobalSettings settings, de.kortty.policy.EffectivePolicy policy) {
        return settings != null && settings.isOsc52ClipboardWriteEnabled()
            && (policy == null || policy.osc52ClipboardWriteAllowed());
    }

    /**
     * Decodes {@code write} and puts its text on {@code clipboard} when {@code allowed}; nothing is
     * decoded, let alone written, when not. A write too long to keep is refused as too large.
     */
    static Outcome write(ClipboardWrite write, boolean allowed, Consumer<String> clipboard) {
        if (!allowed) {
            return Outcome.blocked();
        }
        if (write.tooLarge()) {
            return Outcome.refused(Rejection.TOO_LARGE);
        }
        Decoded decoded = Osc52Support.decode(write.data());
        String text = decoded.text();
        if (text == null) {
            return Outcome.refused(Objects.requireNonNull(decoded.rejection()));
        }
        clipboard.accept(text);
        return Outcome.written(text, decoded.bytes());
    }

    /**
     * The status bar's message for {@code outcome}, naming the tab by its name without control or
     * bidi characters; {@code null} for an empty write, which is not worth one. The tab's name, which
     * a program can set, is always the last placeholder: {@code LanguageManager} fills them one after
     * another, so it could otherwise fill the next one from inside the name.
     *
     * @param i18n the translations, {@code I18n::get}
     */
    static @Nullable String statusText(Outcome outcome, @Nullable String tabName,
            BiFunction<String, Object[], String> i18n) {
        String name = DisplayTextSanitizer.sanitize(tabName, MAX_TAB_NAME_CHARS);
        String shown = name.isEmpty() ? "?" : name;
        return switch (outcome.result()) {
            case WRITTEN -> i18n.apply("terminal.osc52.copied", new Object[] {outcome.characters(), shown});
            case BLOCKED -> i18n.apply("terminal.osc52.blocked", new Object[] {shown});
            case REJECTED -> i18n.apply("terminal.osc52.rejected", new Object[] {shown});
            case IGNORED -> null;
        };
    }

    private static void showInWindow(TerminalTab tab, String message) {
        TabPane tabPane = tab.getTabPane();
        Window window = tabPane != null && tabPane.getScene() != null ? tabPane.getScene().getWindow() : null;
        MainWindow mainWindow = MainWindow.findByStage(window);
        if (mainWindow != null) {
            mainWindow.showStatusMessage(message);
        }
    }
}
