package de.kortty.shellintegration;

/**
 * The OSC sequences korTTY takes out of a pane's output instead of handing them to SithTermFX,
 * which ignores all of them anyway. Every other OSC (titles, OSC 7, OSC 8 links, every other
 * {@code 777} subcommand such as {@code korTTY-agent}) passes through untouched.
 *
 * <p>The cap bounds the payload korTTY keeps, so a sequence that never ends or is absurdly long
 * costs no memory: anything longer is dropped up to its terminator and reported as
 * {@link ShellIntegrationEvent.Oversize}.
 */
public enum OwnedOsc {

    /** {@code OSC 133 ; A|B|C|D ...}: FinalTerm shell-integration marks. */
    SHELL_INTEGRATION("133;", 4096),
    /** {@code OSC 9 ; text}: the iTerm2/ConEmu desktop notification. */
    NOTIFICATION("9;", 4096),
    /** {@code OSC 777 ; notify ; title ; body}: the urxvt/foot desktop notification. */
    NOTIFY("777;notify;", 4096);

    private final String prefix;
    private final int maxPayloadLength;

    OwnedOsc(String prefix, int maxPayloadLength) {
        this.prefix = prefix;
        this.maxPayloadLength = maxPayloadLength;
    }

    /** What the OSC body starts with, numeric code and separator included. */
    public String prefix() {
        return prefix;
    }

    /** The most chars of payload (the body after {@link #prefix()}) that are kept. */
    public int maxPayloadLength() {
        return maxPayloadLength;
    }
}
