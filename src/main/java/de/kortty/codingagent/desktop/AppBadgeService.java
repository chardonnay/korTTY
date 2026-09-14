package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Application-icon badge showing the number of coding agents waiting for a decision.
 *
 * <p>The service coalesces equal counts, honours the {@code enabled} setting (off → the badge is
 * cleared once, then nothing is applied), asks for attention only for an urgent positive count and
 * degrades to the {@link TitleBadgePresenter} ("(n) KorTTY") whenever the backend reports itself
 * unsupported or fails twice in a row — logged once, for the rest of the session; title and icon
 * are never stacked. {@link #update} and {@link #refresh} must run on the JavaFX thread: the
 * Windows backend snapshots a canvas and the presenters touch stages.
 */
public final class AppBadgeService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AppBadgeService.class);
    private static final int FAILURES_BEFORE_FALLBACK = 2;

    private final AppBadgeBackend backend;
    private final TitleBadgePresenter titleFallback;
    private final BooleanSupplier enabled;
    private int lastRequestedCount;
    private int lastAppliedCount = -1;
    private int consecutiveFailures;
    private boolean titleFallbackActive;
    private boolean fallbackLogged;
    private boolean closed;

    /**
     * Creates the service.
     *
     * @param backend the platform backend (an {@code Unsupported} backend switches straight to the
     *     title fallback)
     * @param titleFallback the window-title presenter used where no icon badge is available
     * @param enabled the {@code codingAgentAppBadgeEnabled} setting, read on every update
     */
    public AppBadgeService(AppBadgeBackend backend, TitleBadgePresenter titleFallback, BooleanSupplier enabled) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.titleFallback = Objects.requireNonNull(titleFallback, "titleFallback");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /**
     * Applies {@code blockedCount} (coalescing an unchanged count) and, when {@code urgent} and the
     * effective count is positive, asks the desktop for attention. JavaFX thread only.
     *
     * <p>An unchanged count is re-applied when the backend has just given up asynchronously (a
     * failed launcher-entry emit on its own executor flips {@code isSupported()} to false long after
     * {@code showCount} returned): the count that caused the failure would otherwise appear neither
     * on the icon nor in the title until it changes again.
     */
    public void update(int blockedCount, boolean urgent) {
        if (closed) {
            return;
        }
        lastRequestedCount = Math.max(0, blockedCount);
        int effective = effectiveCount();
        boolean fallbackJustActivated = !titleFallbackActive && usingTitleFallback();
        if (effective != lastAppliedCount || fallbackJustActivated) {
            apply(effective);
        }
        if (urgent && effective > 0) {
            requestAttention();
        }
    }

    /**
     * Re-applies the current count unconditionally — for windows created later, after the setting
     * changed, and at start-up to clear a stale badge. JavaFX thread only.
     */
    public void refresh() {
        if (closed) {
            return;
        }
        apply(effectiveCount());
    }

    /** The count last handed to the backend or the title presenter ({@code 0} before any update). */
    public int lastAppliedCount() {
        return Math.max(0, lastAppliedCount);
    }

    /** True once the service shows the count in the window title instead of on the icon. */
    public boolean usingTitleFallback() {
        if (!titleFallbackActive && !backendSupported()) {
            activateTitleFallback("badge backend unsupported on this platform");
        }
        return titleFallbackActive;
    }

    /** Clears the badge best-effort (fire-and-forget on the backend) and ignores later updates. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            backend.close();
        } catch (Throwable t) {
            logger.debug("Could not close the app badge backend", t);
        }
        if (titleFallbackActive && lastAppliedCount > 0) {
            try {
                titleFallback.applyTitleCount(0);
            } catch (Throwable t) {
                logger.debug("Could not clear the window-title badge", t);
            }
        }
    }

    private int effectiveCount() {
        return enabled.getAsBoolean() ? lastRequestedCount : 0;
    }

    private void apply(int count) {
        lastAppliedCount = count;
        if (usingTitleFallback()) {
            applyTitle(count);
            return;
        }
        try {
            backend.showCount(count);
            consecutiveFailures = 0;
        } catch (Throwable t) {
            consecutiveFailures++;
            logger.debug("App badge update failed ({} in a row)", consecutiveFailures, t);
            if (consecutiveFailures >= FAILURES_BEFORE_FALLBACK) {
                activateTitleFallback("badge backend failed " + consecutiveFailures + " times in a row");
                applyTitle(count);
            }
        }
    }

    private void requestAttention() {
        if (usingTitleFallback()) {
            return;
        }
        try {
            backend.requestAttention();
        } catch (Throwable t) {
            logger.debug("App badge attention request failed", t);
        }
    }

    private void applyTitle(int count) {
        try {
            titleFallback.applyTitleCount(count);
        } catch (Throwable t) {
            logger.debug("Window-title badge update failed", t);
        }
    }

    private boolean backendSupported() {
        try {
            return backend.isSupported();
        } catch (Throwable t) {
            logger.debug("App badge backend support probe failed", t);
            return false;
        }
    }

    private void activateTitleFallback(String reason) {
        titleFallbackActive = true;
        if (!fallbackLogged) {
            fallbackLogged = true;
            logger.info("Showing the blocked coding-agent count in the window title: {}", reason);
        }
    }
}
