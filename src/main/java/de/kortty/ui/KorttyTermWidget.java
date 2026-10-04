package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalMode;
import com.sithtermfx.core.TerminalOutputStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.TerminalCopyPasteHandler;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.core.PolicyAwareCopyPasteHandler;
import de.kortty.core.TerminalLinkDetector;
import de.kortty.paste.PasteSource;
import de.kortty.paste.PasteTarget;
import de.kortty.ui.TerminalLinkClickPolicy.Hit;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import javafx.application.Platform;
import javafx.geometry.Dimension2D;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.text.Font;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * korTTY's terminal widget: a {@link SithTermFxWidget} whose panel routes every copy/paste path
 * (shortcuts, context menu, middle-click/primary selection) through the policy-aware clipboard
 * handler, so the enterprise policy's internal-clipboard mode covers the terminal completely.
 *
 * <p>Once a {@link PasteHandler} is installed, every paste leaves SithTermFX's own paste code: the
 * paste action, the context menu, Edit → Paste and a local middle-click hand the text and
 * {@link #pasteTarget()} to the handler, which decides what reaches the pane. The widget also
 * reports whether the program in the pane really has bracketed paste enabled
 * ({@link #isBracketedPasteMode()}), which SithTermFX's own flag gets wrong after a terminal reset.
 *
 * <p>It also exposes the context-menu commands as {@link TerminalPaneActions}, calling SithTermFX's
 * public API directly. The panel is a subclass, {@link KorttyTerminalPanel}, so a declared-method
 * lookup on its runtime class misses every SithTermFX method that it does not override itself.
 *
 * <p>OSC 8 links go through {@link KorttyOsc8LinkInfoProvider}, which keeps only web and mail links
 * and, in a pane with a {@linkplain #setFileLinkHandler file handler}, {@code file:} links, and open
 * only on a single, still Cmd/Ctrl+click: SithTermFX navigates them on that gesture only, and the
 * pane opens a navigated link only when {@link TerminalLinkClickPolicy}'s filter saw that click on
 * it ({@link KorttyTerminalPanel#followLink}). The same click opens a web or
 * e-mail address or a file path printed as plain text, which {@link TerminalLinkResolver} finds on
 * demand for the kinds set with {@link #setPlainTextLinkKinds}; none until then. A file opens as text
 * in the Snippet Editor through the file handler, never with another program. Resting the mouse on a
 * link shows its target ({@link TerminalLinkHoverController}), and an OSC 8 link whose text names
 * another host than it opens asks first ({@link TerminalLinkMismatchDialog}). A right-click on a link
 * adds Open Link and Copy Link Address, or Open File and Copy Path, to the context menu
 * ({@link #contextMenuLink()}).
 *
 * <p>Every bell the program in the pane rings goes to the {@linkplain #setBellListener bell listener}
 * as well; the bell itself stays silent.
 *
 * <p>korTTY's own key actions for the pane, such as Previous Prompt and Next Prompt, come before
 * SithTermFX's ({@link #setLeadingTerminalActions}): SithTermFX and the split pane's key routing both
 * let the first action whose key matches decide, and an action that is disabled at the time leaves
 * the key to the program in the pane.
 */
public class KorttyTermWidget extends SithTermFxWidget implements TerminalPaneActions {

    private static final Logger logger = LoggerFactory.getLogger(KorttyTermWidget.class);

    /** Points per Increase/Decrease step of the context menu's font-size submenu. */
    static final float FONT_SIZE_STEP = 2f;

    /**
     * Reads the text of a paste. The panel's own handler is private to SithTermFX; this one behaves
     * the same (it keeps no state), including the enterprise policy's internal-clipboard mode.
     */
    private static final PolicyAwareCopyPasteHandler PASTE_READER = new PolicyAwareCopyPasteHandler();

    /** Receives a pane's pastes instead of SithTermFX's own paste code. */
    @FunctionalInterface
    public interface PasteHandler {

        /**
         * Called on the JavaFX thread for every paste into the pane.
         *
         * @param target the pane, the same object for every paste
         * @param text the clipboard or selection text, unchanged
         * @param source where the text came from
         */
        void paste(@NotNull PasteTarget target, @NotNull String text, @NotNull PasteSource source);
    }

    private final WidgetPasteTarget pasteTarget = new WidgetPasteTarget();

    private @Nullable PasteHandler pasteHandler;

    private Supplier<String> pasteTargetLabel = () -> "";

    private Supplier<Charset> pasteTargetCharset = () -> StandardCharsets.UTF_8;

    private BooleanSupplier pasteTargetBroadcast = () -> false;

    private BooleanSupplier pasteTargetMultiExec = () -> false;

    public KorttyTermWidget(int columns, int lines, SettingsProvider settingsProvider) {
        super(columns, lines, settingsProvider);
        // Replace SithTermFX's default OSC 8 provider before the pane is started: it opens file:
        // links with java.awt.Desktop.open, so remote output could launch a local program. korTTY's
        // keeps a file: link only while the pane's file handler opens files, as text.
        KorttyTerminalPanel panel = (KorttyTerminalPanel) getTerminalPanel();
        setLinkInfoProvider(new KorttyOsc8LinkInfoProvider(panel::fileLinksEnabled, panel::followLink));
        // A filter runs before SithTermFX's own click handler on the canvas, so consuming the click
        // keeps SithTermFX from pasting the selection itself. Mouse reports to the program go out
        // on press and release, which this leaves alone. It takes only the middle button; the
        // panel's link click filter takes the primary one.
        getTerminalPanel().getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED, this::pasteOnMiddleClick);
    }

    /**
     * Routes the pane's pastes to {@code handler}; null gives them back to SithTermFX. JavaFX thread.
     */
    public void setPasteHandler(@Nullable PasteHandler handler) {
        pasteHandler = handler;
    }

    /**
     * What {@link #pasteTarget()} reports about the pane beyond what the widget knows itself.
     *
     * @param label how the user knows the pane, usually the connection's name
     * @param charset the encoding the pane's connector sends text in
     * @param broadcastActive whether broadcast mode is on in the pane's tab
     */
    public void describePasteTarget(@NotNull Supplier<String> label, @NotNull Supplier<Charset> charset,
            @NotNull BooleanSupplier broadcastActive) {
        pasteTargetLabel = Objects.requireNonNull(label, "label");
        pasteTargetCharset = Objects.requireNonNull(charset, "charset");
        pasteTargetBroadcast = Objects.requireNonNull(broadcastActive, "broadcastActive");
    }

    /**
     * {@link #describePasteTarget(Supplier, Supplier, BooleanSupplier)}, plus whether the pane takes
     * part in multi-exec, which {@link PasteTarget#multiExecActive()} reports.
     *
     * @param multiExecActive whether what the pane receives as user input is mirrored to a multi-exec
     *     group
     */
    public void describePasteTarget(@NotNull Supplier<String> label, @NotNull Supplier<Charset> charset,
            @NotNull BooleanSupplier broadcastActive, @NotNull BooleanSupplier multiExecActive) {
        describePasteTarget(label, charset, broadcastActive);
        pasteTargetMultiExec = Objects.requireNonNull(multiExecActive, "multiExecActive");
    }

    /** The pane as a paste target: one object for the widget's whole life, keyed by the widget. */
    public @NotNull PasteTarget pasteTarget() {
        return pasteTarget;
    }

    /**
     * Whether the program in the pane has bracketed paste (DECSET 2004) enabled. SithTermFX keeps a
     * flag that a terminal reset ({@code ESC c}, the {@code reset} command) does not clear, so this
     * also asks the emulator, whose mode a reset does clear. See {@link #effectiveBracketedPasteMode}.
     */
    public boolean isBracketedPasteMode() {
        boolean panelFlag = getTerminalPanel() instanceof KorttyTerminalPanel panel && panel.bracketedPasteMode;
        return effectiveBracketedPasteMode(panelFlag, getTerminal());
    }

    /**
     * Forgets the pane's bracketed-paste state, for a new session on this widget: the program that
     * enabled it is gone, and the next one enables it again when it handles bracketed paste.
     */
    public void resetBracketedPasteMode() {
        getTerminalPanel().setBracketedPasteMode(false);
    }

    /**
     * Whether a pane is in bracketed-paste mode: the program switched it on (the flag the emulator
     * reports to the panel) and the emulator still has the mode, which a terminal reset clears
     * without telling the panel.
     *
     * @param panelFlag the last value the emulator reported through {@code setBracketedPasteMode}
     * @param terminal the pane's emulator; a terminal other than SithTermFX's is trusted on the flag
     */
    static boolean effectiveBracketedPasteMode(boolean panelFlag, @Nullable Terminal terminal) {
        return panelFlag
            && (!(terminal instanceof SithTerminal sith) || sith.isModelEnabled(TerminalMode.BracketedPasteMode));
    }

    /**
     * Whether SithTermFX would paste the selection for this click: a middle-click with paste on
     * middle-click enabled that the terminal handles itself instead of reporting it to the program.
     */
    static boolean isLocalMiddleClickPaste(@Nullable MouseButton button, boolean pasteOnMiddleClick,
            boolean localMouseAction) {
        return button == MouseButton.MIDDLE && pasteOnMiddleClick && localMouseAction;
    }

    private void pasteOnMiddleClick(MouseEvent event) {
        PasteHandler handler = pasteHandler;
        if (handler == null || event.isConsumed()) {
            return;
        }
        TerminalPanel panel = getTerminalPanel();
        if (!isLocalMiddleClickPaste(event.getButton(), getSettingsProvider().pasteOnMiddleMouseClick(),
                panel.isLocalMouseAction(event))) {
            return;
        }
        // SithTermFX focuses the pane on every click; the consumed click no longer reaches it.
        panel.getCanvas().requestFocus();
        event.consume();
        paste(handler, PasteSource.SELECTION);
    }

    private void paste(PasteHandler handler, PasteSource source) {
        String text = PASTE_READER.getContents(source == PasteSource.SELECTION);
        if (text != null) {
            handler.paste(pasteTarget, text, source);
        }
    }

    @Override
    protected TerminalPanel createTerminalPanel(@NotNull SettingsProvider settingsProvider,
            @NotNull StyleState styleState, @NotNull TerminalTextBuffer terminalTextBuffer) {
        // Runs inside the SithTermFxWidget constructor, before this widget's own fields are set.
        return new KorttyTerminalPanel(settingsProvider, terminalTextBuffer, styleState);
    }

    @Override
    public void copySelection() {
        // Keep the selection, and use the regular clipboard rather than the X11 primary selection.
        getTerminalPanel().handleCopy(false, false);
    }

    @Override
    public void paste() {
        getTerminalPanel().handlePaste();
    }

    @Override
    public void clearBuffer() {
        getTerminalPanel().clearBuffer();
    }

    @Override
    public void showFind() {
        showFindComponent();
    }

    @Override
    public void increaseFontSize() {
        increaseFontSize(FONT_SIZE_STEP);
    }

    @Override
    public void decreaseFontSize() {
        decreaseFontSize(FONT_SIZE_STEP);
    }

    /**
     * Sets which kinds of links a Cmd/Ctrl+click finds in plain text, such as
     * {@link TerminalLinkResolver#WEB_LINK_KINDS}. The supplier is asked on every click, so a change
     * of the setting behind it applies at once; an empty set leaves only OSC 8 links. Call it on the
     * JavaFX thread.
     */
    public void setPlainTextLinkKinds(@NotNull Supplier<Set<TerminalLinkDetector.Kind>> kinds) {
        ((KorttyTerminalPanel) getTerminalPanel()).setPlainTextLinkKinds(kinds);
    }

    /**
     * Sets what opens the files that links in this pane point to: paths printed as plain text (when
     * {@link #setPlainTextLinkKinds} includes them) and OSC 8 {@code file:} links. Without a handler,
     * or while it is not {@link TerminalFileLinkHandler#enabled()}, no file opens and OSC 8
     * {@code file:} targets stay plain text. Call it on the JavaFX thread, before the pane starts.
     */
    public void setFileLinkHandler(@Nullable TerminalFileLinkHandler handler) {
        ((KorttyTerminalPanel) getTerminalPanel()).fileLinkHandler = handler;
    }

    /**
     * Whether {@link #openLink} opens {@code link}: it has a web or mail target, or a file this
     * pane's file handler accepts. Call it on the JavaFX thread.
     */
    public boolean opens(@NotNull TerminalLinkResolver.Link link) {
        return ((KorttyTerminalPanel) getTerminalPanel()).openable(Objects.requireNonNull(link, "link")).opens();
    }

    /**
     * Sets where the underline of a hovered link is drawn: the pane's {@code LINKS} overlay layer,
     * which {@code TerminalSplitPane} creates on first use. The supplier may return {@code null}
     * while there is no layer; the hover then shows the cursor and the tooltip only.
     */
    public void setLinkOverlay(@NotNull Supplier<Pane> overlay) {
        ((KorttyTerminalPanel) getTerminalPanel()).setLinkOverlay(overlay);
    }

    /**
     * Sets who is told that a program in the pane rang the bell (BEL). It runs on the pane's emulator
     * thread, once for every BEL in the output, so it must be cheap and must not block: hand the
     * work on, coalesced, as {@link de.kortty.shellintegration.BellCoalescer} does. {@code null}
     * stops the reports. The bell stays silent either way.
     */
    public void setBellListener(@Nullable Runnable listener) {
        ((KorttyTerminalPanel) getTerminalPanel()).bellListener = listener;
    }

    /**
     * Sets korTTY's key actions for this pane, which SithTermFX tries before its own (copy, paste,
     * scrolling, find). Each is asked whether it is enabled on every matching key press; a disabled
     * one leaves the key to the program in the pane, exactly as if it did not exist. Keep them hidden
     * ({@link TerminalAction#withHidden}): korTTY builds the pane's context menu itself. An empty list
     * removes them. Call it on the JavaFX thread.
     */
    public void setLeadingTerminalActions(@NotNull List<TerminalAction> actions) {
        ((KorttyTerminalPanel) getTerminalPanel()).leadingActions = List.copyOf(actions);
    }

    /**
     * Whether {@code action} is one of korTTY's own key actions for this pane
     * ({@link #setLeadingTerminalActions}) rather than one of SithTermFX's. Compared by identity: the
     * pane's action list hands out the instances that were set. Any thread.
     */
    public boolean isLeadingTerminalAction(@Nullable TerminalAction action) {
        if (action == null) {
            return false;
        }
        List<TerminalAction> own = ((KorttyTerminalPanel) getTerminalPanel()).leadingActions;
        if (own == null) {
            return false;
        }
        for (TerminalAction candidate : own) {
            if (candidate == action) {
                return true;
            }
        }
        return false;
    }

    /**
     * The link the terminal's context menu was opened on: the one under the last right-button press
     * in this pane, as it was at that press, or {@code null} when that press was on no link or another
     * press came after it. The menu offers Open Link and Copy Link Address for it (Open File in Snippet
     * Editor and Copy Path for a file), see
     * {@link TerminalLinkContextMenu}. Call it on the JavaFX thread.
     */
    public @Nullable TerminalLinkResolver.Link contextMenuLink() {
        return ((KorttyTerminalPanel) getTerminalPanel()).linkMenu.link();
    }

    /**
     * Opens {@code link} exactly as a Cmd/Ctrl+click on it does: a web or mail target through
     * {@link TerminalLinkOpener}'s allowlist, a file through the pane's file handler, and an OSC 8
     * link whose text names another host only after the user confirms. A link that does not
     * {@linkplain #opens open} does nothing. Call it on the JavaFX thread.
     */
    public void openLink(@NotNull TerminalLinkResolver.Link link) {
        ((KorttyTerminalPanel) getTerminalPanel()).openLink(Objects.requireNonNull(link, "link").hit());
    }

    /**
     * The widget's terminal panel, korTTY's subclass of SithTermFX's {@link TerminalPanel}. korTTY's
     * overrides of the panel's methods belong here. It is an inner class because
     * {@code clearBuffer(boolean)} needs the widget's terminal.
     */
    public final class KorttyTerminalPanel extends TerminalPanel {

        /** A copy of SithTermFX's private bracketed-paste flag, which the emulator sets through the panel. */
        private volatile boolean bracketedPasteMode;

        /** Where a Cmd/Ctrl+click sends a link. */
        private TerminalLinkOpener linkOpener = TerminalLinkOpener.system();

        /** The kinds of links found in plain text; none until the terminal view sets them. */
        private Supplier<Set<TerminalLinkDetector.Kind>> plainTextLinkKinds = Set::of;

        /** The pane's overlay layer for the hover underline; none until the split pane sets it. */
        private Supplier<Pane> linkOverlay = () -> null;

        /** Asks before an OSC 8 link whose text names another host opens. */
        private TerminalLinkMismatchDialog.Confirmation mismatchConfirmation = TerminalLinkMismatchDialog::confirm;

        /** Opens the files links point to; none until the terminal view sets it. Read on the emulator thread too. */
        private volatile @Nullable TerminalFileLinkHandler fileLinkHandler;

        /** Told about every bell; none until the terminal view sets it. Called on the emulator thread. */
        private volatile @Nullable Runnable bellListener;

        /** korTTY's key actions, tried before SithTermFX's; none until the terminal view sets them. */
        private volatile List<TerminalAction> leadingActions = List.of();

        private final TerminalLinkHoverController linkHover;

        private final TerminalLinkContextMenu linkMenu;

        /**
         * The OSC 8 link a click with the open gesture landed on, noted by the click filter and
         * dropped once SithTermFX's click handler has run; JavaFX thread only.
         */
        private @Nullable GestureClick gestureClick;

        /** A click with the open gesture on an OSC 8 link: the link's {@code LinkInfo} and what it opens. */
        private record GestureClick(@NotNull LinkInfo linkInfo, @NotNull Hit hit) {
        }

        KorttyTerminalPanel(@NotNull SettingsProvider settingsProvider, @NotNull TerminalTextBuffer terminalTextBuffer,
                @NotNull StyleState styleState) {
            super(settingsProvider, terminalTextBuffer, styleState);
            // The pane's live link kinds; a file the file handler does not open is no link target.
            TerminalLinkHoverController.LinkFinder linkFinder =
                (buffer, cell) -> openable(TerminalLinkResolver.linkAt(buffer, cell, plainTextLinkKinds.get()));
            // Links open only on a single, still Cmd/Ctrl+click. A canvas filter, so it runs before
            // SithTermFX's handler: it opens a plain-text link itself and notes the OSC 8 link of an
            // open gesture, which SithTermFX then navigates (followLink).
            TerminalLinkClickPolicy.install(this, (buffer, cell) -> {
                TerminalLinkResolver.Link link = linkFinder.linkAt(buffer, cell);
                return link != null ? link.hit() : Hit.NONE;
            }, this::openLink, this::noteOsc8Click);
            // Hover: underline, cursor and target tooltip. Its MOUSE_MOVED handler follows in init().
            linkHover = TerminalLinkHoverController.install(this, linkFinder, () -> linkOverlay.get());
            // The link under a right-button press, for Open Link and Copy Link Address in the context menu.
            linkMenu = TerminalLinkContextMenu.install(this, linkFinder);
        }

        void setPlainTextLinkKinds(@NotNull Supplier<Set<TerminalLinkDetector.Kind>> kinds) {
            plainTextLinkKinds = Objects.requireNonNull(kinds, "kinds");
        }

        void setLinkOverlay(@NotNull Supplier<Pane> overlay) {
            linkOverlay = Objects.requireNonNull(overlay, "overlay");
        }

        /** Whether OSC 8 {@code file:} targets become links now; asked on the emulator thread. */
        boolean fileLinksEnabled() {
            TerminalFileLinkHandler handler = fileLinkHandler;
            return handler != null && handler.enabled();
        }

        /** {@code link}, without its file when this pane does not open that file. */
        @Contract("null -> null; !null -> !null")
        @Nullable TerminalLinkResolver.Link openable(@Nullable TerminalLinkResolver.Link link) {
            if (link == null || link.file() == null || acceptsFile(link.file())) {
                return link;
            }
            return link.withoutFile();
        }

        private boolean acceptsFile(@NotNull TerminalFileLink file) {
            TerminalFileLinkHandler handler = fileLinkHandler;
            return handler != null && handler.accepts(file);
        }

        /**
         * Replaces the system browser as the target of Cmd/Ctrl+clicked links, for
         * {@code terminalLinksSmoke}. Call it on the JavaFX thread.
         */
        void setLinkOpener(@NotNull TerminalLinkOpener opener) {
            linkOpener = Objects.requireNonNull(opener, "opener");
        }

        /**
         * Replaces the host-mismatch dialog with a stand-in, for {@code terminalLinksSmoke}. Call it
         * on the JavaFX thread.
         */
        void setMismatchConfirmation(@NotNull TerminalLinkMismatchDialog.Confirmation confirmation) {
            mismatchConfirmation = Objects.requireNonNull(confirmation, "confirmation");
        }

        /** The hover handling of this panel, for {@code terminalLinksSmoke}. */
        @NotNull TerminalLinkHoverController linkHover() {
            return linkHover;
        }

        /** The font the panel draws its regular text in, at the current size; for quick select's labels. */
        @NotNull Font terminalFont() {
            return createFont();
        }

        /** Notes the OSC 8 link of a click with the open gesture, before SithTermFX handles the click. */
        private void noteOsc8Click(@NotNull Point cell, @NotNull Hit hit) {
            LinkInfo linkInfo = TerminalLinkResolver.linkInfoAt(getTerminalTextBuffer(), cell);
            gestureClick = linkInfo != null ? new GestureClick(linkInfo, hit) : null;
        }

        /**
         * Opens an OSC 8 link SithTermFX navigated, which it does on a single, still click with the
         * open gesture. It opens only the link the click filter saw that click land on, so the
         * gesture, the host-mismatch question and the file handler apply whatever the settings
         * provider or the vendor's cell mapping would let through; anything else does nothing.
         */
        void followLink(@NotNull KorttyLinkInfo link) {
            GestureClick click = gestureClick;
            gestureClick = null;
            if (click != null && click.linkInfo() == link) {
                openLink(click.hit());
            }
        }

        /**
         * Opens a Cmd/Ctrl+clicked link. An OSC 8 link whose text names another host than its target
         * opens only after the user confirms; the question is asked once the click is handled.
         */
        private void openLink(@NotNull Hit hit) {
            TerminalFileLink file = hit.file();
            if (file != null) {
                openFile(hit, file);
                return;
            }
            URI target = hit.target();
            if (target == null) {
                return;
            }
            Optional<String> shownHost = hit.kind() == HitKind.OSC8
                ? TerminalLinkOpener.visibleHostMismatch(hit.text(), target)
                : Optional.empty();
            if (shownHost.isEmpty()) {
                linkOpener.open(target);
                return;
            }
            Platform.runLater(() -> {
                if (mismatchConfirmation.confirm(getCanvas(), shownHost.get(), target)) {
                    linkOpener.open(target);
                }
            });
        }

        /**
         * Opens a Cmd/Ctrl+clicked file through the file handler, if it accepts the file now. An OSC 8
         * link whose text shows a web address asks first, as for a web link: the file it opens is not
         * the address it shows.
         */
        private void openFile(@NotNull Hit hit, @NotNull TerminalFileLink file) {
            TerminalFileLinkHandler handler = fileLinkHandler;
            if (handler == null || !handler.accepts(file)) {
                return;
            }
            URI target = file.uri();
            Optional<String> shownHost = hit.kind() == HitKind.OSC8 && target != null
                ? TerminalLinkOpener.visibleHostMismatch(hit.text(), target)
                : Optional.empty();
            if (shownHost.isEmpty()) {
                handler.open(file);
                return;
            }
            Platform.runLater(() -> {
                if (mismatchConfirmation.confirm(getCanvas(), shownHost.get(), target)) {
                    handler.open(file);
                }
            });
        }

        /**
         * SithTermFX adds its mouse handlers here, after the constructor. The hover's
         * {@code MOUSE_MOVED} handler goes after them, so the cursor it sets wins over SithTermFX's,
         * and so does the {@code MOUSE_CLICKED} handler that drops a noted gesture click.
         */
        @Override
        public void init() {
            super.init();
            linkHover.followMouseMoves();
            // After SithTermFX's click handler, which navigates a link while the noted click is fresh.
            getCanvas().addEventHandler(MouseEvent.MOUSE_CLICKED, event -> gestureClick = null);
        }

        @Override
        protected TerminalCopyPasteHandler createCopyPasteHandler() {
            return new PolicyAwareCopyPasteHandler();
        }

        /**
         * Every paste except the middle-click one ends here: the paste action, the context menu and
         * Edit → Paste. With a {@link PasteHandler} installed the handler gets the text instead of
         * SithTermFX, which would send embedded paste markers on as they are.
         */
        @Override
        public void handlePaste() {
            PasteHandler handler = pasteHandler;
            if (handler == null) {
                super.handlePaste();
                return;
            }
            KorttyTermWidget.this.paste(handler, PasteSource.CLIPBOARD);
        }

        /**
         * Every bell of every emulation ends here: the panel is the terminal's display, and the
         * emulators ring through {@code Terminal.beep()}. The listener hears it first, then
         * SithTermFX's own handling runs, which stays silent because korTTY's settings provider
         * answers {@code audibleBell()} with false. This runs on the emulator thread for every BEL,
         * thousands of them when a binary file is printed, and a failing listener must not stop
         * that thread.
         */
        @Override
        public void beep() {
            Runnable listener = bellListener;
            if (listener != null) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    // The emulator thread keeps reading; a lost bell report is all that happens.
                    logger.debug("Bell listener failed: {}", e.toString());
                }
            }
            super.beep();
        }

        /**
         * SithTermFX's key actions with korTTY's in front ({@link #setLeadingTerminalActions}).
         * SithTermFX builds a new list on every key press and runs the first action whose key matches;
         * the split pane's key routing asks the same list, so both see korTTY's first.
         */
        @Override
        public List<TerminalAction> getActions() {
            List<TerminalAction> own = leadingActions;
            List<TerminalAction> actions = super.getActions();
            // Null only if SithTermFX asked while this panel's own fields were not set yet.
            if (own == null || own.isEmpty()) {
                return actions;
            }
            List<TerminalAction> all = new ArrayList<>(own.size() + actions.size());
            all.addAll(own);
            all.addAll(actions);
            return all;
        }

        @Override
        public void setBracketedPasteMode(boolean bracketedPasteModeEnabled) {
            super.setBracketedPasteMode(bracketedPasteModeEnabled);
            bracketedPasteMode = bracketedPasteModeEnabled;
        }

        @Override
        protected void clearBuffer(boolean keepLastLine) {
            super.clearBuffer(keepLastLine);
            // When SithTermFX keeps the prompt line it moves the emulator cursor to row 0, one row
            // above the screen (rows count from 1). Until the next output corrects it, every
            // repaint logs two "line out of bounds" errors, and a line feed lands on the kept
            // prompt line instead of below it. Clamp it back onto the screen the way the next
            // write would. This covers the context menu and SithTermFX's own clear shortcut.
            if (KorttyTermWidget.this.getTerminal() instanceof SithTerminal terminal) {
                terminal.scrollY();
            }
        }

        /**
         * The cell under a point in canvas coordinates, mapped the way SithTermFX's own selection
         * and link hit-testing map it. See {@link TerminalCellGeometry#cellAt(double, double)}.
         *
         * @return the column ({@code x}) and buffer line ({@code y}, negative in the history), or
         *         {@code null} when the point is on no cell of the text buffer
         */
        public @Nullable Point cellAt(double x, double y) {
            TerminalCellGeometry geometry = cellGeometry();
            return geometry != null ? geometry.cellAt(x, y) : null;
        }

        /**
         * A snapshot of the panel's current cell grid, including the cell width and the left inset
         * that SithTermFX keeps protected. Call it on the FX thread and use it right away: a
         * scroll, resize or font change makes it stale.
         *
         * @return the geometry, or {@code null} before the panel has measured its font
         */
        public @Nullable TerminalCellGeometry cellGeometry() {
            Dimension2D charSize = myCharSize;
            if (charSize == null) {
                return null;
            }
            TerminalTextBuffer buffer = getTerminalTextBuffer();
            // The laid-out terminal size is private to TerminalPanel. Its public pixel size is that
            // many cells of a whole-pixel cell size (plus the inset), so dividing recovers it exactly.
            int columns = (int) Math.round((getPixelWidth() - getInsetX()) / charSize.getWidth());
            int rows = (int) Math.round(getPixelHeight() / charSize.getHeight());
            return new TerminalCellGeometry(getInsetX(), charSize.getWidth(), charSize.getHeight(), columns, rows,
                getScrollOrigin(), buffer.getWidth(), buffer.getHeight(), buffer.getHistoryLinesCount());
        }
    }

    /**
     * The widget as a {@link PasteTarget}. It writes through the panel's terminal output stream, the
     * call and the single writer thread SithTermFX's own paste uses, so type-ahead, korTTY's input
     * filters, recordings and the session journal see a paste exactly as before.
     */
    private final class WidgetPasteTarget implements PasteTarget {

        @Override
        public Object key() {
            return KorttyTermWidget.this;
        }

        @Override
        public boolean bracketedPasteMode() {
            return isBracketedPasteMode();
        }

        @Override
        public boolean canReceive() {
            TtyConnector connector = getTtyConnector();
            return getTerminalPanel().getTerminalOutputStream() != null && connector != null && connector.isConnected();
        }

        @Override
        public Object session() {
            return getTtyConnector();
        }

        @Override
        public void send(String payload) {
            TerminalOutputStream output = getTerminalPanel().getTerminalOutputStream();
            if (output != null) {
                output.sendString(payload, true);
            }
        }

        @Override
        public String label() {
            String label = pasteTargetLabel.get();
            return label != null ? label : "";
        }

        @Override
        public Charset charset() {
            Charset charset = pasteTargetCharset.get();
            return charset != null ? charset : StandardCharsets.UTF_8;
        }

        @Override
        public boolean broadcastActive() {
            return pasteTargetBroadcast.getAsBoolean();
        }

        @Override
        public boolean multiExecActive() {
            return pasteTargetMultiExec.getAsBoolean();
        }
    }
}
