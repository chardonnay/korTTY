package de.kortty.shellintegration;

import org.jetbrains.annotations.Nullable;

/**
 * Something a program in a terminal pane announced with one of the OSC sequences korTTY owns
 * ({@link OwnedOsc}). {@link OscEventSplitter} cuts them out of the output in stream order, so a
 * consumer that runs when the event is delivered sees the screen exactly as it was at that point.
 *
 * <p>Text in an event is what the remote side sent, unchanged and unsanitised: whoever shows it
 * must clean it first, and nothing here may be logged above DEBUG. {@link #summary()} is the
 * loggable form; it never contains remote text.
 */
public sealed interface ShellIntegrationEvent {

    /** A short description for logs that leaves out every char the remote side chose. */
    String summary();

    /** {@code OSC 133;A}: the shell starts drawing its prompt. */
    record PromptStart() implements ShellIntegrationEvent {
        @Override
        public String summary() {
            return "PromptStart";
        }
    }

    /** {@code OSC 133;B}: the prompt is drawn; what follows is the command line being typed. */
    record CommandStart() implements ShellIntegrationEvent {
        @Override
        public String summary() {
            return "CommandStart";
        }
    }

    /** {@code OSC 133;C}: the command was submitted; its output follows. */
    record OutputStart() implements ShellIntegrationEvent {
        @Override
        public String summary() {
            return "OutputStart";
        }
    }

    /**
     * {@code OSC 133;D[;status]}: the command finished.
     *
     * @param exitStatus the first integer parameter, or {@code null} when the shell sent none
     */
    record CommandFinished(@Nullable Integer exitStatus) implements ShellIntegrationEvent {
        @Override
        public String summary() {
            return "CommandFinished(exit=" + (exitStatus != null ? exitStatus : "none") + ")";
        }
    }

    /**
     * {@code OSC 9;text} or {@code OSC 777;notify;title;body}: a program asks for a desktop
     * notification. {@link RemoteNotificationText} reads the payload and cleans the text for showing.
     *
     * @param source which sequence carried it
     * @param title  the title of an OSC 777 notification, or {@code null} when there is none
     *               (always for OSC 9)
     * @param body   the text; never empty
     */
    record RemoteNotification(OwnedOsc source, @Nullable String title, String body)
            implements ShellIntegrationEvent {
        @Override
        public String summary() {
            return "RemoteNotification(" + source + ", title="
                    + (title != null ? title.length() + " chars" : "none")
                    + ", body=" + body.length() + " chars)";
        }
    }

    /**
     * {@code OSC 52;Pc;data}: a program asks to put text on the clipboard. Only writes are events:
     * a query ({@code data} is {@code ?}) is never one, so the clipboard is never read back to the
     * remote side. {@link Osc52Support#decode} checks and decodes {@code data}; whether anything is
     * written is up to the setting.
     *
     * @param selection the {@code Pc} parameter as sent: empty or made of {@code c p q s 0-7}, all of
     *                  which mean the one clipboard korTTY has
     * @param data      the base64 text as sent, line breaks and all; never empty unless
     *                  {@code tooLarge}
     * @param tooLarge  whether the sequence was longer than {@link OwnedOsc#CLIPBOARD} keeps
     *                  ({@link #overCap()}): nothing of it was kept, so {@code selection} and
     *                  {@code data} are empty, and the write can only be refused
     */
    record ClipboardWrite(String selection, String data, boolean tooLarge) implements ShellIntegrationEvent {

        /** A write as the program sent it. */
        public ClipboardWrite(String selection, String data) {
            this(selection, data, false);
        }

        /**
         * A write too long to keep, which the splitter reports as {@link Oversize} of
         * {@link OwnedOsc#CLIPBOARD}: still a program's attempt to change the clipboard, so whoever
         * handles writes can say that it was refused.
         */
        public static ClipboardWrite overCap() {
            return new ClipboardWrite("", "", true);
        }

        @Override
        public String summary() {
            return tooLarge ? "ClipboardWrite(too large)" : "ClipboardWrite(" + data.length() + " chars)";
        }
    }

    /** An owned sequence longer than {@link OwnedOsc#maxPayloadLength()}: dropped unread. */
    record Oversize(OwnedOsc kind) implements ShellIntegrationEvent {
        @Override
        public String summary() {
            return "Oversize(" + kind + ")";
        }
    }
}
