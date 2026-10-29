package de.kortty.ui;

import com.sithtermfx.core.clipboard.ClipboardError;
import com.sithtermfx.core.clipboard.ClipboardException;
import com.sithtermfx.core.clipboard.ClipboardLocation;
import com.sithtermfx.core.clipboard.ClipboardReadRequest;
import com.sithtermfx.core.clipboard.ClipboardWriteRequest;
import com.sithtermfx.core.clipboard.TerminalClipboardHandler;
import de.kortty.core.DisplayTextSanitizer;
import de.kortty.core.KorttyClipboard;
import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import de.kortty.shellintegration.Osc52Support;
import javafx.application.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What a program in a terminal tab may do with the clipboard through {@code OSC 5522}, kitty's clipboard
 * protocol, which SithTermFX parses and answers. One per tab, set on each of its panes
 * ({@code SithTermFxWidget.setClipboardHandler}) unless the pane runs in the strict terminal mode.
 *
 * <p>The rules are those of {@code OSC 52} ({@link TerminalClipboardWriter}), plus reading:
 * <ul>
 *   <li><b>Writes</b> need {@code GlobalSettings.osc52ClipboardWriteEnabled} and a policy that does not
 *       forbid clipboard writes. Only text is taken ({@code text/plain}, or another type aliased to it),
 *       strict UTF-8 of at most {@link Osc52Support#MAX_DECODED_BYTES}; anything else is refused. The
 *       text goes through {@link KorttyClipboard}, so the internal clipboard mode keeps it in korTTY.</li>
 *   <li><b>Reads</b> need {@code GlobalSettings.osc5522ClipboardReadEnabled} and a policy that does not
 *       forbid them ({@code allow-osc5522-clipboard-read}), and then the user's answer to a prompt that
 *       names the tab, the program as it calls itself and the MIME types. A read the user granted by
 *       pasting (a paste event) is not asked again; neither is one of a program the user allowed for the
 *       tab's life, which needs its name and password. After a Deny the tab refuses reads without asking
 *       for {@link #DENY_QUIET_SECONDS} seconds, so a program cannot bury the user in prompts. Only text is
 *       handed out, from {@link KorttyClipboard}: in the internal clipboard mode, korTTY's own.</li>
 *   <li><b>Listing the types</b> needs no permission. It reports {@code text/plain} when
 *       {@link KorttyClipboard} has text, and nothing else, as no other type is ever handed out.</li>
 *   <li>The primary selection is answered with {@code ENOSYS}.</li>
 * </ul>
 *
 * <p>SithTermFX calls on the emulator thread, which must not wait: every check that needs the clipboard
 * or the user runs on the JavaFX thread and completes the returned stage there. The status bar names the
 * tab for every write and read, and when the settings stopped one; the log records sizes, never data.
 */
public final class TerminalClipboardAccess implements TerminalClipboardHandler {

    private static final Logger logger = LoggerFactory.getLogger(TerminalClipboardAccess.class);

    /** The only MIME type korTTY reads and writes for programs. */
    static final String TEXT_PLAIN = "text/plain";

    /** How long a tab refuses reads without asking after the user denied one. */
    static final long DENY_QUIET_SECONDS = 10;

    /** At most this many characters of a program's name, a MIME type or the tab's name are shown. */
    static final int MAX_SHOWN_CHARS = 80;

    /** At most this many MIME types are listed in the prompt. */
    static final int MAX_SHOWN_TYPES = 8;

    /** The user's answer to a read prompt. */
    enum ReadDecision {
        ALLOW_ONCE,
        /** Also allow later reads of the program with the same name and password, for the tab's life. */
        ALLOW_FOR_PROGRAM,
        DENY
    }

    /**
     * What the read prompt shows; every string is already cleaned of control and bidi characters.
     *
     * @param tabName     the tab's name, never empty
     * @param programName the name the program gives itself, or {@code null}
     * @param mimeTypes   the types it asks for, at most {@link #MAX_SHOWN_TYPES}
     * @param moreTypes   how many further types it asks for
     * @param canRemember whether "Allow for this program" is offered: the program sent a name and password
     */
    record ReadPromptRequest(String tabName, @Nullable String programName, List<String> mimeTypes, int moreTypes,
            boolean canRemember) {
    }

    /** Asks the user about a read; called and answered on the JavaFX thread. */
    @FunctionalInterface
    interface ReadPrompt {
        void ask(ReadPromptRequest request, Consumer<ReadDecision> answer);
    }

    /** The clipboard the protocol reads and writes: {@link KorttyClipboard}. JavaFX thread. */
    interface TextClipboard {
        @Nullable String getText();

        void setText(String text);

        boolean hasText();
    }

    /** The tab a handler belongs to. JavaFX thread. */
    interface Host {
        /** The tab's name, as a program may have set it. */
        @Nullable String tabName();

        /** Shows {@code message} in the status bar of the tab's window. */
        void showStatus(String message);
    }

    private static final TextClipboard KORTTY_CLIPBOARD = new TextClipboard() {
        @Override
        public @Nullable String getText() {
            return KorttyClipboard.getText();
        }

        @Override
        public void setText(String text) {
            KorttyClipboard.setText(text);
        }

        @Override
        public boolean hasText() {
            return KorttyClipboard.hasText();
        }
    };

    private final Supplier<GlobalSettings> settings;
    private final Supplier<EffectivePolicy> policy;
    private final TextClipboard clipboard;
    private final Host host;
    private final ReadPrompt prompt;
    private final Executor fxThread;
    private final LongSupplier nanoTime;
    private final BiFunction<String, Object[], String> i18n;

    /** The programs allowed to read for the tab's life: name and password. */
    private final Set<String> allowedPrograms = ConcurrentHashMap.newKeySet();

    /** {@link #nanoTime} of the last Deny, or {@code null}; JavaFX thread. */
    private @Nullable Long lastDeny;

    private volatile boolean closed;

    TerminalClipboardAccess(Supplier<GlobalSettings> settings, Supplier<EffectivePolicy> policy,
            TextClipboard clipboard, Host host, ReadPrompt prompt, Executor fxThread, LongSupplier nanoTime,
            BiFunction<String, Object[], String> i18n) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.host = Objects.requireNonNull(host, "host");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.fxThread = Objects.requireNonNull(fxThread, "fxThread");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    /** The handler of {@code tab}: korTTY's settings, policy and clipboard, the read prompt in its window. */
    static TerminalClipboardAccess forTab(TerminalTab tab) {
        Host host = new Host() {
            @Override
            public @Nullable String tabName() {
                return tab.getEffectiveTitle();
            }

            @Override
            public void showStatus(String message) {
                TerminalClipboardWriter.showInWindow(tab, message);
            }
        };
        ClipboardReadPromptDialog dialog = new ClipboardReadPromptDialog(() ->
            tab.getTabPane() != null && tab.getTabPane().getScene() != null ? tab.getTabPane().getScene().getWindow() : null);
        return new TerminalClipboardAccess(TerminalAttentionNotifier::currentSettings,
            de.kortty.policy.PolicyManager::effective, KORTTY_CLIPBOARD, host, dialog::ask, Platform::runLater,
            System::nanoTime, I18n::get);
    }

    /** The tab is gone: every request from now on is refused, and an open prompt's answer is ignored. */
    void close() {
        closed = true;
        allowedPrograms.clear();
    }

    /**
     * Whether a program may write the clipboard: as for {@code OSC 52}, the setting in Settings → Terminal is
     * on and the policy does not forbid it. Asked on every write, so a hand-edited settings file cannot let one
     * through.
     */
    static boolean writeAllowed(@Nullable GlobalSettings settings, @Nullable EffectivePolicy policy) {
        return TerminalClipboardWriter.allowed(settings, policy);
    }

    /** Whether a program may ask to read the clipboard: the setting is on and the policy does not forbid it. */
    static boolean readAllowed(@Nullable GlobalSettings settings, @Nullable EffectivePolicy policy) {
        return settings != null && settings.isOsc5522ClipboardReadEnabled()
            && (policy == null || policy.osc5522ClipboardReadAllowed());
    }

    /**
     * Whether a paste may go out as an {@code OSC 5522} paste event, which lets the program read the clipboard
     * without a prompt instead of receiving the text: only while reads are allowed, and never while the policy
     * sets a paste-warning floor, as korTTY's paste warning would not see the text.
     */
    boolean pasteEventsAllowed() {
        EffectivePolicy current = policy.get();
        return !closed && readAllowed(settings.get(), current) && (current == null || current.pasteWarningFloor() == null);
    }

    @Override
    public @NotNull CompletionStage<List<String>> getMimeTypes(@NotNull ClipboardLocation location) {
        if (location != ClipboardLocation.CLIPBOARD) {
            return failed(ClipboardError.ENOSYS);
        }
        return onFxThread(() -> {
            if (closed) {
                throw new ClipboardException(ClipboardError.EPERM);
            }
            return clipboard.hasText() ? List.of(TEXT_PLAIN) : List.of();
        });
    }

    @Override
    public @NotNull CompletionStage<Void> write(@NotNull ClipboardWriteRequest request) {
        if (request.getLocation() != ClipboardLocation.CLIPBOARD) {
            return failed(ClipboardError.ENOSYS);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        fxThread.execute(() -> {
            try {
                writeOnFxThread(request);
                result.complete(null);
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void writeOnFxThread(ClipboardWriteRequest request) throws ClipboardException {
        if (closed) {
            throw new ClipboardException(ClipboardError.EPERM);
        }
        if (!writeAllowed(settings.get(), policy.get())) {
            logger.debug("OSC 5522 clipboard write not allowed by the settings");
            status("terminal.osc5522.writeBlocked");
            throw new ClipboardException(ClipboardError.EPERM);
        }
        byte[] bytes = textOf(request);
        if (bytes == null) {
            logger.debug("OSC 5522 clipboard write without text ignored: {} type(s)", request.getData().size());
            status("terminal.osc5522.writeRejected");
            throw new ClipboardException(ClipboardError.EPERM, "only text/plain");
        }
        if (bytes.length > Osc52Support.MAX_DECODED_BYTES) {
            logger.debug("OSC 5522 clipboard write of {} bytes ignored: too large", bytes.length);
            status("terminal.osc5522.writeRejected");
            throw new ClipboardException(ClipboardError.EFBIG);
        }
        String text = decodeUtf8(bytes);
        if (text == null) {
            logger.debug("OSC 5522 clipboard write of {} bytes ignored: not UTF-8", bytes.length);
            status("terminal.osc5522.writeRejected");
            throw new ClipboardException(ClipboardError.EIO, "not UTF-8 text");
        }
        if (text.isEmpty()) {
            // As OSC 52: an empty write leaves the clipboard alone and is not worth a message.
            return;
        }
        clipboard.setText(text);
        logger.info("A program in a terminal pane put {} bytes on the clipboard (OSC 5522)", bytes.length);
        status("terminal.osc5522.copied", text.codePointCount(0, text.length()));
    }

    @Override
    public @NotNull CompletionStage<Map<String, byte[]>> read(@NotNull ClipboardReadRequest request) {
        if (request.getLocation() != ClipboardLocation.CLIPBOARD) {
            return failed(ClipboardError.ENOSYS);
        }
        List<String> textTypes = textTypes(request.getMimeTypes());
        CompletableFuture<Map<String, byte[]>> result = new CompletableFuture<>();
        fxThread.execute(() -> {
            try {
                readOnFxThread(request, textTypes, result);
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void readOnFxThread(ClipboardReadRequest request, List<String> textTypes,
            CompletableFuture<Map<String, byte[]>> result) throws ClipboardException {
        if (closed) {
            throw new ClipboardException(ClipboardError.EPERM);
        }
        if (!readAllowed(settings.get(), policy.get())) {
            logger.debug("OSC 5522 clipboard read not allowed by the settings or the policy");
            status("terminal.osc5522.readBlocked");
            throw new ClipboardException(ClipboardError.EPERM);
        }
        if (textTypes.isEmpty()) {
            // Nothing korTTY would hand out was asked for: nothing to ask the user about.
            result.complete(Map.of());
            return;
        }
        String program = programKey(request.getName(), request.getPassword());
        if (request.isGrantedByPaste() || (program != null && allowedPrograms.contains(program))) {
            result.complete(readText(textTypes));
            return;
        }
        Long denied = lastDeny;
        if (denied != null && nanoTime.getAsLong() - denied < TimeUnit.SECONDS.toNanos(DENY_QUIET_SECONDS)) {
            logger.debug("OSC 5522 clipboard read refused without asking: denied moments ago");
            throw new ClipboardException(ClipboardError.EPERM);
        }
        prompt.ask(promptRequest(request), decision -> {
            try {
                if (closed || decision == null || decision == ReadDecision.DENY) {
                    lastDeny = nanoTime.getAsLong();
                    result.completeExceptionally(new ClipboardException(ClipboardError.EPERM));
                    return;
                }
                // Asked again: settings or policy may have changed while the prompt was open.
                if (!readAllowed(settings.get(), policy.get())) {
                    result.completeExceptionally(new ClipboardException(ClipboardError.EPERM));
                    return;
                }
                if (decision == ReadDecision.ALLOW_FOR_PROGRAM && program != null) {
                    allowedPrograms.add(program);
                }
                result.complete(readText(textTypes));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
    }

    private Map<String, byte[]> readText(List<String> textTypes) {
        String text = clipboard.getText();
        if (text == null || text.isEmpty()) {
            return Map.of();
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> data = new LinkedHashMap<>();
        for (String type : textTypes) {
            data.put(type, bytes);
        }
        logger.info("A program in a terminal pane read {} bytes from the clipboard (OSC 5522)", bytes.length);
        status("terminal.osc5522.read", text.codePointCount(0, text.length()));
        return data;
    }

    ReadPromptRequest promptRequest(ClipboardReadRequest request) {
        String name = request.getName() != null ? DisplayTextSanitizer.sanitize(request.getName(), MAX_SHOWN_CHARS) : "";
        List<String> types = new ArrayList<>();
        int more = 0;
        for (String type : request.getMimeTypes()) {
            if (types.size() == MAX_SHOWN_TYPES) {
                more++;
                continue;
            }
            String shown = DisplayTextSanitizer.sanitize(type, MAX_SHOWN_CHARS);
            if (!shown.isEmpty()) {
                types.add(shown);
            }
        }
        return new ReadPromptRequest(tabName(), name.isEmpty() ? null : name, List.copyOf(types), more,
            programKey(request.getName(), request.getPassword()) != null);
    }

    /**
     * The text of a write: the data of the first type that is {@code text/plain} or aliased to it, or
     * {@code null} when there is none.
     */
    static byte @Nullable [] textOf(ClipboardWriteRequest request) {
        for (Map.Entry<String, byte[]> entry : request.getData().entrySet()) {
            if (isText(entry.getKey())) {
                return entry.getValue();
            }
            for (String alias : request.getAliases().getOrDefault(entry.getKey(), List.of())) {
                if (isText(alias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** The requested types korTTY answers, {@code text/plain} with or without parameters, in their order. */
    static List<String> textTypes(List<String> requested) {
        List<String> text = new ArrayList<>();
        for (String type : requested) {
            if (isText(type) && !text.contains(type)) {
                text.add(type);
            }
        }
        return text;
    }

    /** Whether {@code mimeType} is {@code text/plain}, ignoring case and parameters such as a charset. */
    static boolean isText(String mimeType) {
        int semicolon = mimeType.indexOf(';');
        String base = (semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType).trim().toLowerCase(Locale.ROOT);
        return base.equals(TEXT_PLAIN);
    }

    private static @Nullable String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }

    private static @Nullable String programKey(@Nullable String name, @Nullable String password) {
        return name != null && password != null ? name + '\0' + password : null;
    }

    private String tabName() {
        String name = DisplayTextSanitizer.sanitize(host.tabName(), MAX_SHOWN_CHARS);
        return name.isEmpty() ? "?" : name;
    }

    /**
     * Shows a message naming the tab; the tab's name, which a program can set, is always the last
     * placeholder, as in {@link TerminalClipboardWriter#statusText}.
     */
    private void status(String key, Object... arguments) {
        Object[] withTab = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        withTab[arguments.length] = tabName();
        try {
            host.showStatus(i18n.apply(key, withTab));
        } catch (RuntimeException e) {
            logger.debug("Showing an OSC 5522 status message failed: {}", e.toString());
        }
    }

    private <T> CompletionStage<T> onFxThread(FxCall<T> call) {
        CompletableFuture<T> result = new CompletableFuture<>();
        fxThread.execute(() -> {
            try {
                result.complete(call.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private static <T> CompletionStage<T> failed(ClipboardError error) {
        return CompletableFuture.failedFuture(new ClipboardException(error));
    }

    @FunctionalInterface
    private interface FxCall<T> {
        T call() throws Exception;
    }
}
