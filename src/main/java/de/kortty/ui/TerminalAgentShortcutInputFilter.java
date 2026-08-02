package de.kortty.ui;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Stateful byte filter for terminal agent shortcuts.
 *
 * <p>The connector write path sees keyboard input and clipboard paste alike, so this is the only
 * reliable place to assemble the command submitted with Enter. The filter deliberately remains
 * independent of JavaFX and connector implementations; {@link TerminalView} supplies the command
 * policy and dispatch callbacks.</p>
 */
final class TerminalAgentShortcutInputFilter {

    static final byte CLEAR_INPUT_LINE = 0x15; // Ctrl+U

    private static final char ESCAPE = '\u001B';
    private static final char CTRL_C = '\u0003';
    private static final char CTRL_U = '\u0015';
    private static final char DELETE = '\u007F';
    private static final String BRACKETED_PASTE_START = "\u001B[200~";
    private static final String BRACKETED_PASTE_END = "\u001B[201~";

    private enum ControlSequenceState {
        NONE,
        ESCAPE,
        CSI,
        SS3,
        OSC,
        OSC_ESCAPE
    }

    private final Function<String, String> commandResolver;
    private final Predicate<String> shellHandlesCommand;
    private final Predicate<String> commandInterceptor;
    private final Consumer<String> commandDispatcher;
    /** Optional observer of every submitted line (session journal input capture); may be null. */
    private final Consumer<String> submittedLineSink;
    private final StringBuilder inputLine = new StringBuilder();
    private final StringBuilder controlSequence = new StringBuilder();
    private final ByteArrayOutputStream partialUtf8 = new ByteArrayOutputStream(4);

    private boolean swallowNextLineFeed;
    private boolean bracketedPaste;
    private ControlSequenceState controlSequenceState = ControlSequenceState.NONE;

    TerminalAgentShortcutInputFilter(
        Function<String, String> commandResolver,
        Predicate<String> shellHandlesCommand,
        Predicate<String> commandInterceptor,
        Consumer<String> commandDispatcher) {
        this(commandResolver, shellHandlesCommand, commandInterceptor, commandDispatcher, null);
    }

    TerminalAgentShortcutInputFilter(
        Function<String, String> commandResolver,
        Predicate<String> shellHandlesCommand,
        Predicate<String> commandInterceptor,
        Consumer<String> commandDispatcher,
        Consumer<String> submittedLineSink) {

        this.commandResolver = Objects.requireNonNull(commandResolver, "commandResolver");
        this.shellHandlesCommand = Objects.requireNonNull(shellHandlesCommand, "shellHandlesCommand");
        this.commandInterceptor = Objects.requireNonNull(commandInterceptor, "commandInterceptor");
        this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher");
        this.submittedLineSink = submittedLineSink;
    }

    /** Filters one connector write while preserving decoding and paste state across writes. */
    synchronized byte[] filter(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream outgoing = new ByteArrayOutputStream(bytes.length);
        int[] currentLineStart = {0};
        for (byte value : bytes) {
            processByte(value & 0xFF, outgoing, currentLineStart);
        }
        return outgoing.toByteArray();
    }

    private void processByte(int value, ByteArrayOutputStream outgoing, int[] currentLineStart) {
        while (true) {
            if (partialUtf8.size() > 0) {
                if (isUtf8Continuation(value)) {
                    partialUtf8.write(value);
                    if (partialUtf8.size() == expectedUtf8Length(partialUtf8.toByteArray()[0] & 0xFF)) {
                        byte[] characterBytes = partialUtf8.toByteArray();
                        partialUtf8.reset();
                        emitUtf8Character(characterBytes, outgoing, currentLineStart);
                    }
                    return;
                }
                flushPartialUtf8(outgoing);
                continue;
            }

            if (value < 0x80) {
                byte[] originalBytes = {(byte) value};
                processCharacter(String.valueOf((char) value), originalBytes, outgoing, currentLineStart);
                return;
            }

            if (expectedUtf8Length(value) > 1) {
                partialUtf8.write(value);
                return;
            }

            outgoing.write(value);
            return;
        }
    }

    private void emitUtf8Character(
        byte[] characterBytes,
        ByteArrayOutputStream outgoing,
        int[] currentLineStart) {

        if (!isWellFormedUtf8(characterBytes)) {
            outgoing.writeBytes(characterBytes);
            return;
        }
        processCharacter(
            new String(characterBytes, StandardCharsets.UTF_8),
            characterBytes,
            outgoing,
            currentLineStart);
    }

    private void processCharacter(
        String text,
        byte[] originalBytes,
        ByteArrayOutputStream outgoing,
        int[] currentLineStart) {

        if (text == null || text.isEmpty()) {
            return;
        }

        char first = text.charAt(0);
        if (controlSequenceState != ControlSequenceState.NONE || first == ESCAPE) {
            processControlSequenceCharacter(first);
            outgoing.writeBytes(originalBytes);
            return;
        }

        if (bracketedPaste) {
            // Newlines inside bracketed paste are input text, not an Enter submission. Keep them in
            // the assembled command and let the shell's bracketed-paste mode display them normally.
            inputLine.append(text);
            outgoing.writeBytes(originalBytes);
            return;
        }

        if (text.length() == 1 && swallowNextLineFeed && first == '\n') {
            swallowNextLineFeed = false;
            return;
        }
        swallowNextLineFeed = false;
        if (text.length() == 1 && isLineBreak(first)) {
            handleLineBreak(first, originalBytes, outgoing, currentLineStart);
            return;
        }

        updateInputLine(text);
        outgoing.writeBytes(originalBytes);
    }

    private void handleLineBreak(
        char ch,
        byte[] originalBytes,
        ByteArrayOutputStream outgoing,
        int[] currentLineStart) {

        String rawCommand = commandResolver.apply(inputLine.toString());
        resetInputLine();

        if (submittedLineSink != null) {
            try {
                submittedLineSink.accept(rawCommand);
            } catch (RuntimeException e) {
                // The journal sink must never break the input path.
            }
        }

        if (shellHandlesCommand.test(rawCommand)) {
            outgoing.writeBytes(originalBytes);
            currentLineStart[0] = outgoing.size();
            return;
        }
        if (commandInterceptor.test(rawCommand)) {
            // Bytes from earlier writes are already visible in the PTY. Ctrl+U clears those; bytes
            // for the current logical line that are still in this write are removed before sending.
            truncate(outgoing, currentLineStart[0]);
            outgoing.write(CLEAR_INPUT_LINE);
            if (ch == '\r') {
                swallowNextLineFeed = true;
            }
            currentLineStart[0] = outgoing.size();
            commandDispatcher.accept(rawCommand);
            return;
        }

        outgoing.writeBytes(originalBytes);
        currentLineStart[0] = outgoing.size();
    }

    private void processControlSequenceCharacter(char ch) {
        if (controlSequenceState == ControlSequenceState.NONE) {
            controlSequence.setLength(0);
            controlSequence.append(ch);
            controlSequenceState = ControlSequenceState.ESCAPE;
            return;
        }

        controlSequence.append(ch);
        switch (controlSequenceState) {
            case ESCAPE -> {
                if (ch == '[') {
                    controlSequenceState = ControlSequenceState.CSI;
                } else if (ch == 'O') {
                    controlSequenceState = ControlSequenceState.SS3;
                } else if (ch == ']') {
                    controlSequenceState = ControlSequenceState.OSC;
                } else {
                    finishControlSequence();
                }
            }
            case CSI -> {
                if (ch >= '@' && ch <= '~') {
                    finishControlSequence();
                }
            }
            case SS3 -> finishControlSequence();
            case OSC -> {
                if (ch == '\u0007') {
                    finishControlSequence();
                } else if (ch == ESCAPE) {
                    controlSequenceState = ControlSequenceState.OSC_ESCAPE;
                }
            }
            case OSC_ESCAPE -> {
                if (ch == '\\') {
                    finishControlSequence();
                } else {
                    controlSequenceState = ch == ESCAPE
                        ? ControlSequenceState.OSC_ESCAPE
                        : ControlSequenceState.OSC;
                }
            }
            case NONE -> {
                // Handled before the switch.
            }
        }
    }

    private void finishControlSequence() {
        String sequence = controlSequence.toString();
        if (BRACKETED_PASTE_START.equals(sequence)) {
            bracketedPaste = true;
        } else if (BRACKETED_PASTE_END.equals(sequence)) {
            bracketedPaste = false;
        }
        controlSequence.setLength(0);
        controlSequenceState = ControlSequenceState.NONE;
    }

    private void resetInputLine() {
        inputLine.setLength(0);
        controlSequence.setLength(0);
        controlSequenceState = ControlSequenceState.NONE;
        bracketedPaste = false;
    }

    private boolean isLineBreak(char ch) {
        return ch == '\r' || ch == '\n';
    }

    private void updateInputLine(String text) {
        char first = text.charAt(0);
        if (text.length() == 1 && (first == '\b' || first == DELETE)) {
            if (!inputLine.isEmpty()) {
                int lastCodePoint = inputLine.codePointBefore(inputLine.length());
                inputLine.setLength(inputLine.length() - Character.charCount(lastCodePoint));
            }
            return;
        }
        if (text.length() == 1 && (first == CTRL_U || first == CTRL_C)) {
            resetInputLine();
            return;
        }
        if (first == '\t' || first >= 32) {
            inputLine.append(text);
        }
    }

    private void truncate(ByteArrayOutputStream outgoing, int length) {
        byte[] current = outgoing.toByteArray();
        outgoing.reset();
        outgoing.write(current, 0, Math.min(length, current.length));
    }

    private void flushPartialUtf8(ByteArrayOutputStream outgoing) {
        if (partialUtf8.size() == 0) {
            return;
        }
        outgoing.writeBytes(partialUtf8.toByteArray());
        partialUtf8.reset();
    }

    private boolean isUtf8Continuation(int value) {
        return (value & 0xC0) == 0x80;
    }

    private int expectedUtf8Length(int value) {
        if (value >= 0xC2 && value <= 0xDF) {
            return 2;
        }
        if (value >= 0xE0 && value <= 0xEF) {
            return 3;
        }
        if (value >= 0xF0 && value <= 0xF4) {
            return 4;
        }
        return value < 0x80 ? 1 : -1;
    }

    private boolean isWellFormedUtf8(byte[] value) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(value));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
