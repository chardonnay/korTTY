package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;

/**
 * The character encoding a terminal connector decodes the session output with and encodes typed
 * and pasted text in.
 *
 * <p>The encoding is resolved once per connector, in this order:</p>
 * <ol>
 *   <li><b>Mosh</b> ({@link ConnectionProtocol#MOSH}, {@link ConnectionProtocol#MOSH_CLIENT}) is
 *       always UTF-8: mosh-server and mosh-client refuse to run without a UTF-8 locale, so any
 *       other charset would garble the session. That also covers the SSH bootstrap of a Mosh
 *       session, whose connection keeps its Mosh protocol.</li>
 *   <li>The connection's own override ({@link ServerConnection#getEncoding()}), when it names one
 *       of the {@link #SUPPORTED_ENCODINGS}.</li>
 *   <li>A <b>local shell</b> without an override is UTF-8. korTTY starts it with a UTF-8 locale and
 *       today's local shells (ConPTY on Windows included) emit UTF-8, so a global Latin-1 chosen
 *       for legacy servers would garble every local tab.</li>
 *   <li>The global <i>Settings → Terminal → Encoding</i>, but only once it is confirmed (see
 *       {@link #globalEncoding(GlobalSettings)}).</li>
 *   <li>UTF-8.</li>
 * </ol>
 *
 * <p>Toolkit-free so the order is unit-testable. A change applies on the next connect or reconnect;
 * nothing is switched in an open session.</p>
 */
public final class TerminalEncodingSupport {

    private static final Logger logger = LoggerFactory.getLogger(TerminalEncodingSupport.class);

    /** The encodings the settings and the connection editor offer, in display order. */
    public static final List<String> SUPPORTED_ENCODINGS =
        List.of("UTF-8", "ISO-8859-1", "ISO-8859-15", "Windows-1252");

    private static final List<Charset> SUPPORTED_CHARSETS = SUPPORTED_ENCODINGS.stream()
        .filter(Charset::isSupported)
        .map(Charset::forName)
        .toList();

    private TerminalEncodingSupport() {
    }

    /**
     * The charset {@code name} stands for when it is one of the {@link #SUPPORTED_ENCODINGS}
     * (case-insensitive, aliases such as {@code latin1} included); {@code null} for a blank,
     * malformed, unknown or unsupported name. Never throws.
     */
    public static Charset parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Charset charset;
        try {
            charset = Charset.forName(name.trim());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return null;
        }
        return SUPPORTED_CHARSETS.contains(charset) ? charset : null;
    }

    /**
     * The entry of {@link #SUPPORTED_ENCODINGS} that {@code stored} stands for (so {@code latin1}
     * selects {@code ISO-8859-1}), the trimmed {@code stored} value itself when none does, or
     * {@code null} for a blank value.
     */
    public static String displayName(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        Charset charset = parse(stored);
        if (charset != null) {
            for (String supported : SUPPORTED_ENCODINGS) {
                if (charset.equals(Charset.forName(supported))) {
                    return supported;
                }
            }
        }
        return stored.trim();
    }

    /**
     * The encodings a picker offers: the {@link #SUPPORTED_ENCODINGS}, plus a stored value that none
     * of them stands for (an edited or newer configuration file), so that saving the dialog does not
     * silently drop it. Such a value is kept but not applied.
     */
    public static List<String> offeredEncodings(String stored) {
        String name = displayName(stored);
        if (name == null || SUPPORTED_ENCODINGS.contains(name)) {
            return SUPPORTED_ENCODINGS;
        }
        List<String> offered = new java.util.ArrayList<>(SUPPORTED_ENCODINGS);
        offered.add(name);
        return List.copyOf(offered);
    }

    /** Whether {@code protocol} only works with UTF-8, so the encoding setting does not apply to it. */
    public static boolean isUtf8Only(ConnectionProtocol protocol) {
        return protocol == ConnectionProtocol.MOSH || protocol == ConnectionProtocol.MOSH_CLIENT;
    }

    /**
     * The global encoding that applies to connections without an override, or {@code null} when it
     * does not apply (yet).
     *
     * <p>Migration guard: the setting used to have no effect, so a value someone once picked was
     * stored without ever changing anything. Such a value only takes effect after the Terminal page
     * of the settings has been saved again with this version, which sets
     * {@link GlobalSettings#isTerminalEncodingConfirmed()}. Until then SSH sessions stay UTF-8,
     * exactly as before.</p>
     */
    public static String globalEncoding(GlobalSettings settings) {
        if (settings == null || !settings.isTerminalEncodingConfirmed()) {
            return null;
        }
        ConnectionSettings defaults = settings.getDefaultTerminalSettings();
        return defaults != null ? defaults.getEncoding() : null;
    }

    /**
     * Whether the stored global encoding is a non-UTF-8 value that does not apply yet, because the
     * Terminal page has not been saved since it started to matter.
     */
    public static boolean isGlobalEncodingPending(GlobalSettings settings) {
        if (settings == null || settings.isTerminalEncodingConfirmed()) {
            return false;
        }
        Charset stored = parse(settings.getDefaultTerminalSettings().getEncoding());
        return stored != null && !StandardCharsets.UTF_8.equals(stored);
    }

    /**
     * Records that the settings dialog was saved. The stored global encoding becomes effective when
     * the user saw the Terminal page in that dialog — the page shows the value and, while it is
     * still pending, says that saving applies it.
     */
    public static void confirmOnSave(GlobalSettings settings, boolean terminalPageShown) {
        if (settings != null && terminalPageShown) {
            settings.setTerminalEncodingConfirmed(true);
        }
    }

    /** {@link #resolve(ServerConnection, String)} with the global value taken from {@code settings}. */
    public static Charset resolve(ServerConnection connection, GlobalSettings settings) {
        return resolve(connection, globalEncoding(settings));
    }

    /**
     * The charset for {@code connection}, given the global encoding that applies to it
     * ({@code null} when none does). See the class comment for the order.
     */
    public static Charset resolve(ServerConnection connection, String globalEncoding) {
        ConnectionProtocol protocol = connection != null ? connection.getProtocol() : null;
        if (isUtf8Only(protocol)) {
            return StandardCharsets.UTF_8;
        }
        if (connection != null) {
            String override = connection.getEncoding();
            Charset overrideCharset = parse(override);
            if (overrideCharset != null) {
                return overrideCharset;
            }
            if (override != null && !override.isBlank()) {
                // No connection getter in the log line: see the CodeQL note in SshTtyConnector.connect.
                logger.warn("Ignoring an unsupported per-connection terminal encoding; using the default");
            }
        }
        if (protocol == ConnectionProtocol.LOCAL_SHELL) {
            return StandardCharsets.UTF_8;
        }
        Charset global = parse(globalEncoding);
        if (global != null) {
            return global;
        }
        if (globalEncoding != null && !globalEncoding.isBlank()) {
            logger.warn("Ignoring unsupported global terminal encoding '{}'", sanitize(globalEncoding));
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Resolves the charset from the live {@link GlobalSettings}. Without an application instance or
     * settings (tests, early start-up) the global level is skipped, so the result is the
     * connection's override or UTF-8.
     */
    public static Charset resolveFromSettings(ServerConnection connection) {
        GlobalSettings settings = null;
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
            settings = manager != null ? manager.getSettings() : null;
        } catch (Exception | LinkageError e) {
            logger.debug("Global settings unavailable for the terminal encoding: {}", e.getMessage());
        }
        return resolve(connection, settings);
    }

    /** Whether every character of {@code charset} is one byte, as in ISO-8859-1 or Windows-1252. */
    public static boolean isSingleByte(Charset charset) {
        if (charset == null || !charset.canEncode()) {
            return false;
        }
        return charset.newEncoder().maxBytesPerChar() <= 1.0f;
    }

    /** The stored name as shown in a log line: trimmed, printable ASCII only, bounded. */
    private static String sanitize(String value) {
        String trimmed = value.trim();
        StringBuilder out = new StringBuilder(Math.min(trimmed.length(), 40));
        for (int i = 0; i < trimmed.length() && out.length() < 40; i++) {
            char ch = trimmed.charAt(i);
            out.append(ch >= 0x20 && ch < 0x7F ? ch : '?');
        }
        return out.toString();
    }
}
