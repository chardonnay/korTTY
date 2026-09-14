package de.kortty.codingagent.desktop;

import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Windows taskbar badge: re-renders the stage icon with a counter bubble and hands it to the
 * {@link StageIconPresenter}, which applies it to every open main window. Pure JavaFX; every call
 * happens on the JavaFX thread. Rendered icons are cached per bubble text ({@code 1..9}, {@code 9+}).
 */
final class WindowsIconBadgeBackend implements AppBadgeBackend {

    private static final Logger logger = LoggerFactory.getLogger(WindowsIconBadgeBackend.class);
    private static final String ICON_RESOURCE = "/icon/kortty_icon.png";
    private static final int ICON_SIZE = 256;

    private final StageIconPresenter stageIcons;
    private final Supplier<Image> baseIcon;
    private final Map<String, Image> cache = new HashMap<>();
    private Image base;
    private boolean baseLoaded;

    WindowsIconBadgeBackend(StageIconPresenter stageIcons, Supplier<Image> baseIcon) {
        this.stageIcons = Objects.requireNonNull(stageIcons, "stageIcons");
        this.baseIcon = Objects.requireNonNull(baseIcon, "baseIcon");
    }

    /** Loads the plain application icon from the classpath; {@code null} when missing. */
    static Image loadBaseIcon() {
        try {
            URL url = WindowsIconBadgeBackend.class.getResource(ICON_RESOURCE);
            return url == null ? null : new Image(url.toExternalForm());
        } catch (RuntimeException e) {
            logger.debug("Could not load the application icon for the taskbar badge", e);
            return null;
        }
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public void showCount(int blockedCount) {
        if (blockedCount <= 0) {
            stageIcons.restorePlainIcon();
            return;
        }
        String text = BadgeIconRenderer.badgeText(blockedCount);
        Image badged = cache.get(text);
        if (badged == null) {
            badged = BadgeIconRenderer.render(base(), blockedCount, ICON_SIZE);
            cache.put(text, badged);
        }
        stageIcons.applyBadgedIcon(badged);
    }

    @Override
    public void close() {
        try {
            stageIcons.restorePlainIcon();
        } catch (Throwable t) {
            logger.debug("Could not restore the plain window icon", t);
        }
    }

    private Image base() {
        if (!baseLoaded) {
            baseLoaded = true;
            base = baseIcon.get();
        }
        return base;
    }
}
