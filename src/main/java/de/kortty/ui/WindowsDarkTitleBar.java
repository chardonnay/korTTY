package de.kortty.ui;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the native Windows title bar of every korTTY window follow the system's light/dark app mode.
 *
 * <p>Windows draws the caption of a decorated window light unless the window opts in to the dark frame
 * via {@code DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)}; JavaFX 21 never does that (and has no
 * {@code Platform.Preferences} to follow the color scheme). This helper sets the attribute on the
 * application's top-level windows each time a JavaFX window is shown, and re-reads the
 * {@code AppsUseLightTheme} preference whenever a window gains focus, so a theme switch made in the
 * Windows settings reaches the open windows as soon as the user returns to korTTY.
 *
 * <p>JavaFX gives no public access to a window's {@code HWND}, so the helper enumerates the top-level
 * windows of the JavaFX Application Thread ({@code EnumThreadWindows}): Glass creates every stage,
 * dialog and popup on that thread. The attribute is a no-op on undecorated and transparent windows.
 * Every failure is logged at debug level and leaves the default light frame.
 */
public final class WindowsDarkTitleBar {

    private static final Logger logger = LoggerFactory.getLogger(WindowsDarkTitleBar.class);

    // Documented value since Windows 10 20H1; builds 1809–1909 used the undocumented 19.
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;

    private static final String PERSONALIZE_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String APPS_USE_LIGHT_THEME = "AppsUseLightTheme";

    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    private interface Dwmapi extends StdCallLibrary {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);

        int DwmSetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }

    private static boolean installed;
    private static boolean disabled;
    private static boolean dark;

    private WindowsDarkTitleBar() {
    }

    /**
     * Starts following the system app mode for all current and future windows. Idempotent; a no-op on
     * every platform other than Windows. Safe to call from any thread.
     */
    public static void install() {
        if (!isWindows()) {
            return;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(WindowsDarkTitleBar::install);
            return;
        }
        if (installed) {
            return;
        }
        installed = true;
        dark = readSystemDarkMode();
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    change.getAddedSubList().forEach(WindowsDarkTitleBar::track);
                    // Fallback for a native window that only appears after WINDOW_SHOWN (e.g. a popup).
                    Platform.runLater(() -> applyToThreadWindows(false));
                }
            }
        });
        List<Window> open = new ArrayList<>(Window.getWindows());
        open.forEach(WindowsDarkTitleBar::track);
        if (!open.isEmpty()) {
            applyToThreadWindows(false);
        }
    }

    /** Maps the {@code AppsUseLightTheme} registry value: {@code 0} means dark, anything else (or absent) light. */
    static boolean isDarkAppMode(Integer appsUseLightTheme) {
        return appsUseLightTheme != null && appsUseLightTheme == 0;
    }

    private static void track(Window window) {
        // Window.getWindows() holds a window only while it is showing, so a window that is hidden and
        // re-shown re-enters the list; key the handlers to the window to add them once.
        if (window.getProperties().putIfAbsent(WindowsDarkTitleBar.class, Boolean.TRUE) != null) {
            return;
        }
        window.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> applyToThreadWindows(false));
        window.focusedProperty().addListener((obs, was, focused) -> {
            if (Boolean.TRUE.equals(focused)) {
                refreshSystemMode();
            }
        });
    }

    private static void refreshSystemMode() {
        boolean nowDark = readSystemDarkMode();
        if (nowDark != dark) {
            dark = nowDark;
            applyToThreadWindows(true);
        }
    }

    private static void applyToThreadWindows(boolean repaintFrames) {
        if (disabled) {
            return;
        }
        try {
            int threadId = Kernel32.INSTANCE.GetCurrentThreadId();
            IntByReference value = new IntByReference(dark ? 1 : 0);
            User32.INSTANCE.EnumThreadWindows(threadId, (hwnd, data) -> {
                if (Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4) != 0) {
                    Dwmapi.INSTANCE.DwmSetWindowAttribute(
                        hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, value, 4);
                }
                if (repaintFrames) {
                    // Windows 10 only repaints the caption on the next non-client update.
                    User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
                }
                return true;
            }, null);
        } catch (Throwable t) {
            // Missing dwmapi (pre-Vista) or a JNA linkage problem: keep the default frame from now on.
            disabled = true;
            logger.debug("Dark title bar unavailable; keeping the default window frame", t);
        }
    }

    private static boolean readSystemDarkMode() {
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, APPS_USE_LIGHT_THEME)) {
                return false;
            }
            return isDarkAppMode(Advapi32Util.registryGetIntValue(
                WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, APPS_USE_LIGHT_THEME));
        } catch (Throwable t) {
            logger.debug("Could not read the Windows app mode; assuming light", t);
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
