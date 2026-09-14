package de.kortty.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The non-modal LOCAL_SHELL split, and the only layout mutation the control API performs.
 *
 * <p>Three steps, exactly one of which touches the UI twice: one hop resolves the pane and verifies
 * that its tab is a local shell, the connector is then built and connected <strong>off</strong> the
 * UI thread, and a second hop attaches it. The path never enters
 * {@code TerminalView.doCreateSameServerConnection}, whose {@code APPLICATION_MODAL}
 * {@code connectingStage} would run a nested FX event loop that {@code Platform.runLater} cannot
 * drain.
 *
 * <p>Any thread, never the JavaFX application thread — {@code prepareLocalShellSplitConnector}
 * blocks on a pty spawn and must not run there.
 */
public final class ControlSplitService {

    private static final Logger LOG = LoggerFactory.getLogger(ControlSplitService.class);

    /** The wire name of the split verb. */
    private static final String VERB_SPLIT = "pane.split";

    /** The wire name of the close verb. */
    private static final String VERB_CLOSE = "pane.close";

    /** The two accepted orientations, published in {@code error.data.known}. */
    private static final List<String> ORIENTATIONS = List.of("horizontal", "vertical");

    private final ControlSurface surface;

    private final UiDispatcher ui;

    private final ControlAuditSink audit;

    /**
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param audit the audit sink; {@link ControlAuditSink#LOGGING} when null
     */
    public ControlSplitService(ControlSurface surface, UiDispatcher ui, ControlAuditSink audit) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.audit = audit == null ? ControlAuditSink.LOGGING : audit;
    }

    /**
     * Splits a local-shell pane and returns the <strong>new</strong> pane.
     *
     * @param paneId the resolved source pane id
     * @param orientation {@code horizontal} or {@code vertical}
     * @param focus whether the new pane takes focus
     * @throws ControlApiException {@link ControlErrorCode#INVALID_PARAMS} for another orientation,
     *     {@link ControlErrorCode#PANE_NOT_FOUND}, {@link ControlErrorCode#NOT_CONNECTED},
     *     {@link ControlErrorCode#UNSUPPORTED} for a pane that is not a local shell,
     *     {@link ControlErrorCode#SPLIT_FAILED} when the split aborted
     */
    public PaneInfo split(String paneId, String orientation, boolean focus) throws ControlApiException {
        requirePane(paneId);
        String direction = normaliseOrientation(orientation);
        PaneInfo source = UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS,
            () -> unchecked(() -> surface.resolve(PaneAddress.ofPaneId(paneId))));
        if (!source.connected()) {
            throw new ControlApiException(ControlErrorCode.NOT_CONNECTED,
                "The pane is not connected: " + paneId, Map.of("pane", paneId));
        }
        if (!source.localShell()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pane", paneId);
            data.put("reason", "split is local-shell only");
            data.put("protocol", source.protocol());
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Only a local-shell pane can be split by the control API", data);
        }
        // Off the UI thread on purpose: this connects a pty and would otherwise stall the toolkit.
        Object connector = surface.prepareLocalShellSplitConnector(source.paneId());
        if (connector == null) {
            throw new ControlApiException(ControlErrorCode.SPLIT_FAILED,
                "The split could not be prepared for " + paneId, Map.of("pane", paneId));
        }
        PaneInfo created;
        try {
            created = UiCalls.await(ui, ControlApiProtocol.UI_SPLIT_TIMEOUT_MILLIS,
                () -> unchecked(() -> {
                    PaneInfo pane = surface.attachSplitPane(source.paneId(), direction, connector, focus);
                    if (pane != null) {
                        // Inside the hop, because UiCalls never cancels it: a TIMEOUT here still
                        // attaches a live shell once the toolkit drains, and a pane the API created
                        // must not exist without the audit line that says who created it.
                        record(VERB_SPLIT, source.paneId(), "orientation=" + direction + " focus="
                            + focus + " new_pane=" + pane.paneId());
                    }
                    return pane;
                }));
        } catch (ControlApiException e) {
            // The attach never reached the toolkit, so nothing there can have taken the connector:
            // close the shell this call spawned rather than leave it running with no pane.
            // A TIMEOUT is deliberately NOT closed — UiCalls does not cancel the task, so it may still
            // attach the pane, and closing the connector would gut a live pane.
            if (e.code() == ControlErrorCode.UI_UNAVAILABLE) {
                discard(connector);
            }
            throw e;
        }
        if (created == null) {
            discard(connector);
            throw new ControlApiException(ControlErrorCode.SPLIT_FAILED,
                "The split aborted: the new pane never attached", Map.of("pane", paneId));
        }
        return created;
    }

    /**
     * Closes one split pane.
     *
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#LAST_PANE} for a tab's last pane — closing it would leave an empty
     *     terminal area inside a still-open tab, which is why the user-facing menu item is disabled
     *     in exactly that case
     */
    public void close(String paneId) throws ControlApiException {
        requirePane(paneId);
        try {
            UiCalls.await(ui, ControlApiProtocol.UI_SPLIT_TIMEOUT_MILLIS, () -> unchecked(() -> {
                surface.closePane(paneId);
                // Inside the hop for the same reason as the split above: a hop that missed its
                // budget still closes the pane, and that must not happen unrecorded.
                record(VERB_CLOSE, paneId, "closed=1");
                return Boolean.TRUE;
            }));
        } catch (ControlApiException e) {
            throw e.code() == ControlErrorCode.LAST_PANE ? lastPane(paneId, e) : e;
        }
    }

    /**
     * Closes a prepared connector that no pane will ever own.
     *
     * <p>The connector is opaque here on purpose — the package knows nothing about terminals — but a
     * live pty is behind it, so a split that ends without a pane must not leave the shell process
     * running for the life of the application. {@code ControlSurface.attachSplitPane} closes it on the
     * failure paths it sees itself; this covers the one it cannot, namely never being called at all.
     */
    private static void discard(Object connector) {
        if (!(connector instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debug("control-api: an orphaned split connector could not be closed: {}", e.toString());
        }
    }

    private static ControlApiException lastPane(String paneId, ControlApiException cause) {
        Map<String, Object> data = new LinkedHashMap<>(cause.data());
        data.put("pane", paneId);
        data.put("hint", "close the tab yourself");
        return new ControlApiException(ControlErrorCode.LAST_PANE, cause.getMessage(), data);
    }

    private static String normaliseOrientation(String orientation) throws ControlApiException {
        String value = orientation == null || orientation.isBlank()
            ? ORIENTATIONS.get(0)
            : orientation.strip().toLowerCase(Locale.ROOT);
        if (!ORIENTATIONS.contains(value)) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Unknown split orientation: " + orientation,
                Map.of("param", "orientation", "known", ORIENTATIONS));
        }
        return value;
    }

    private static void requirePane(String paneId) throws ControlApiException {
        if (paneId == null || paneId.isBlank()) {
            throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND, "No pane given",
                Map.of("param", "pane"));
        }
    }

    private void record(String verb, String paneId, String detail) {
        try {
            audit.record(verb, paneId, detail);
        } catch (RuntimeException e) {
            LOG.debug("The control-API audit sink failed for {}: {}", verb, e.toString());
        }
    }

    private static <T> T unchecked(ThrowingSupplier<T> body) {
        try {
            return body.get();
        } catch (ControlApiException e) {
            throw new CompletionException(e);
        }
    }

    /** A supplier whose body may fail with the package's checked exception. */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ControlApiException;
    }
}
