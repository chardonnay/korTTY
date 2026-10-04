package de.kortty.shellintegration;

import de.kortty.core.DisplayTextSanitizer;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A desktop notification a program in a terminal pane asked for, cleaned so it can be shown.
 *
 * <p>Two sequences carry such a request, and {@link OscEventSplitter} takes both out of the output:
 * <ul>
 *   <li>{@code OSC 9 ; text}, the iTerm2 form: the text is everything after {@code 9;}, semicolons
 *       included. ConEmu uses the same code for numbered commands ({@code 9;4;1;50} reports
 *       progress), so a payload that starts with a number followed by {@code ;} or its end is no
 *       notification ({@link #parseOsc9}).</li>
 *   <li>{@code OSC 777 ; notify ; title ; body}, the urxvt and foot form: the body is every
 *       remaining argument joined with {@code ;} again ({@link #parseOsc777Notify}).</li>
 * </ul>
 *
 * <p>The program, often on a server, chooses every character, so the text is cleaned before
 * anything shows it ({@link DisplayTextSanitizer}): C0 and C1 controls, DEL, the Unicode line
 * separators and the bidi controls go, so neither a line break nor a right-to-left override can
 * disguise what it says, the title is cut to {@value #MAX_TITLE_CHARS} characters and the body to
 * {@value #MAX_BODY_CHARS}. Every instance holds clean text: the constructor cleans whatever it is
 * given. Whoever shows it puts the tab's name in front, so a notification always says which tab
 * it came from and can never pass for another application's.
 */
public record RemoteNotificationText(@Nullable String title, String body) {

    /** The most characters of a notification's title that are kept. */
    public static final int MAX_TITLE_CHARS = 80;

    /** The most characters of a notification's body that are kept. */
    public static final int MAX_BODY_CHARS = 200;

    /** Between the title and the body in {@link #text()}. */
    static final String TITLE_SEPARATOR = ": ";

    /** Ends a {@link #summary} that had to be cut. */
    static final String ELLIPSIS = "…";

    /** ConEmu reuses OSC 9 for numbered commands ({@code 9;4;st;pr} is progress), not text. */
    private static final Pattern CONEMU_SUBCOMMAND = Pattern.compile("[0-9]+(;|$)");

    /**
     * Cleans {@code title} and {@code body}. A title with nothing visible becomes {@code null}; a
     * body with nothing visible takes the title's place, so there is always a body.
     *
     * @throws IllegalArgumentException when neither has anything visible; {@link #of} returns
     *                                  {@code null} instead
     */
    public RemoteNotificationText {
        String cleanTitle = clean(title, MAX_TITLE_CHARS);
        String cleanBody = clean(body, MAX_BODY_CHARS);
        if (cleanBody == null) {
            cleanBody = cleanTitle;
            cleanTitle = null;
        }
        if (cleanBody == null) {
            throw new IllegalArgumentException("A notification needs visible text");
        }
        title = cleanTitle;
        body = cleanBody;
    }

    /**
     * The notification {@code title} and {@code body} make, cleaned; {@code null} when neither has
     * anything visible, for example a payload of control characters only.
     */
    public static @Nullable RemoteNotificationText of(@Nullable String title, @Nullable String body) {
        if (clean(title, MAX_TITLE_CHARS) == null && clean(body, MAX_BODY_CHARS) == null) {
            return null;
        }
        return new RemoteNotificationText(title, body);
    }

    /** The notification an {@code OSC 9} or {@code OSC 777;notify} event asks for, cleaned; see {@link #of(String, String)}. */
    public static @Nullable RemoteNotificationText of(ShellIntegrationEvent.RemoteNotification notification) {
        Objects.requireNonNull(notification, "notification");
        return of(notification.title(), notification.body());
    }

    /**
     * Reads the payload of an {@code OSC 9}, the text after {@code 9;}, unchanged and uncleaned.
     *
     * @return the notification, or {@code null} for an empty payload or a ConEmu subcommand such as
     *         the {@code 9;4} progress report
     */
    static @Nullable ShellIntegrationEvent.RemoteNotification parseOsc9(String payload) {
        if (payload.isEmpty() || CONEMU_SUBCOMMAND.matcher(payload).lookingAt()) {
            return null;
        }
        return new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFICATION, null, payload);
    }

    /**
     * Reads the payload of an {@code OSC 777;notify}, the {@code title;body} after
     * {@code 777;notify;}, unchanged and uncleaned. The body is every argument after the title,
     * joined with {@code ;} again; with no body the title becomes the body.
     *
     * @return the notification, or {@code null} when title and body are both empty
     */
    static @Nullable ShellIntegrationEvent.RemoteNotification parseOsc777Notify(String payload) {
        int separator = payload.indexOf(';');
        String title = separator < 0 ? payload : payload.substring(0, separator);
        String body = separator < 0 ? "" : payload.substring(separator + 1);
        if (body.isEmpty()) {
            return title.isEmpty() ? null : new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFY, null, title);
        }
        return new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFY, title.isEmpty() ? null : title, body);
    }

    /**
     * The notification's text: {@code title: body}, or the body alone without a title. A desktop
     * notification shows it below korTTY's own title, which names the tab.
     */
    public String text() {
        return title != null ? title + TITLE_SEPARATOR + body : body;
    }

    /**
     * {@link #text()} in at most {@code maxChars} characters; a longer text is cut and ends with
     * {@value #ELLIPSIS}, never in half a character.
     */
    public String summary(int maxChars) {
        String text = text();
        if (maxChars <= 0) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return DisplayTextSanitizer.truncate(text, maxChars - 1).strip() + ELLIPSIS;
    }

    /** {@code text} cleaned and cut to {@code max}; {@code null} when nothing visible is left. */
    private static @Nullable String clean(@Nullable String text, int max) {
        String clean = DisplayTextSanitizer.sanitize(text, max);
        return clean.isEmpty() ? null : clean;
    }
}
