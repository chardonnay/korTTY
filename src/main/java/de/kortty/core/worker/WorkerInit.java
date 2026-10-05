package de.kortty.core.worker;

/**
 * What korTTY tells a session worker before it connects: where to, as whom, how to authenticate and
 * the token korTTY will log in to the worker's loopback endpoint with. Carries only the secrets this
 * one connection needs, never the vault or the master password; a private key never travels — the
 * worker asks korTTY to sign instead ({@code agent.sign}). Serialized with Gson as the first line on
 * the worker's stdin.
 */
public final class WorkerInit {

    /** The protocol version both sides speak; a worker refuses another one. */
    public static final int PROTOCOL_VERSION = 1;

    public int protocol = PROTOCOL_VERSION;

    /** The target server. */
    public String host;
    public int port = 22;
    public String username;

    /** {@code password} or {@code key} (signed through korTTY). */
    public String auth = "password";

    /** The target password for {@code password} authentication, else null. */
    public String password;

    /** Whether a password prompt of keyboard-interactive must not be answered (a temporary key is in use). */
    public boolean keyOnly;

    public int timeoutSeconds = 15;
    public boolean keepAliveEnabled = true;
    public int keepAliveIntervalSeconds = 60;

    /** The jump server, or null for a direct connection. */
    public Jump jump;

    /** The password korTTY logs in to the worker's loopback endpoint with. */
    public String token;

    /** {@code ssh} (the default) or {@code mosh}: a built-in Mosh session (mosh4j) instead of SSH. */
    public String mode = "ssh";

    /** For {@code mosh}: the UDP port and session key mosh-server reported, and the mosh4j JARs. */
    public int moshPort;
    public String moshKey;
    public java.util.List<String> moshClasspath;

    /** A jump server hop. */
    public static final class Jump {
        public String host;
        public int port = 22;
        public String username;
        /** {@code password} or {@code key}. */
        public String auth = "password";
        public String password;
    }
}
