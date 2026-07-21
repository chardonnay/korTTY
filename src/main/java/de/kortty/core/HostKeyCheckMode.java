package de.kortty.core;

/**
 * How strictly an SSH transport verifies a server's host key on connect.
 *
 * <ul>
 *   <li>{@link #STRICT} — trust-on-first-use: an unknown host key raises a confirmation prompt and
 *       is pinned only on the user's approval; a changed key is refused. The default, and the only
 *       mode used for a jump server's own host key.</li>
 *   <li>{@link #ACCEPT_NEW} — an unknown host key is accepted and pinned WITHOUT a prompt, but a key
 *       that differs from an existing pin is still refused. Mirrors OpenSSH
 *       {@code StrictHostKeyChecking=accept-new}: convenient for throwaway or ephemeral hosts while
 *       still catching a man-in-the-middle on a host you have connected to before.</li>
 * </ul>
 */
public enum HostKeyCheckMode {
    STRICT,
    ACCEPT_NEW
}
