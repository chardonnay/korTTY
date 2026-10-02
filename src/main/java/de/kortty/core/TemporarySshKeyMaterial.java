package de.kortty.core;

import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the private key carried by a {@code TEMPORARY:} key path in memory.
 *
 * <p>A temporary SSH key is pasted by the user and is meant to expire. It used to be written to a
 * {@code kortty_temp_key_*.key} file in the system temp folder on every connect, split and SFTP
 * open. Those files were never deleted, because korTTY quits through {@code Runtime.halt} and so
 * skips {@code deleteOnExit}, which left the key on disk long after it expired. This class parses
 * the key from memory with the same parser {@code FileKeyPairProvider} uses
 * ({@link SecurityUtils#loadKeyPairIdentities}), so PEM, PKCS#8 and OpenSSH keys load exactly as
 * they did from the file.
 *
 * <p>Error messages never contain key material.
 */
public final class TemporarySshKeyMaterial {

    /** Marks a connection key path whose remainder is the private key text itself. */
    public static final String PREFIX = "TEMPORARY:";

    private static final NamedResource RESOURCE = NamedResource.ofName("temporary-ssh-key");

    private TemporarySshKeyMaterial() {
    }

    /** Returns whether {@code keyPath} carries a temporary key instead of a file path. */
    public static boolean isTemporaryKeyPath(String keyPath) {
        return keyPath != null && keyPath.startsWith(PREFIX);
    }

    /**
     * Parses the key pairs of a {@code TEMPORARY:} key path.
     *
     * @param session the session the keys are loaded for; may be {@code null}
     * @param temporaryKeyPath the key path, starting with {@link #PREFIX}
     * @return the complete key pairs (private and public part), never empty
     * @throws IOException when the text holds no usable key; the message never contains key text
     */
    public static List<KeyPair> load(SessionContext session, String temporaryKeyPath) throws IOException {
        if (!isTemporaryKeyPath(temporaryKeyPath)) {
            throw new IllegalArgumentException("Not a temporary SSH key path");
        }
        String content = temporaryKeyPath.substring(PREFIX.length());
        // OpenSSH private keys must end with a newline; a pasted key often lacks it.
        if (!content.endsWith("\n")) {
            content = content + "\n";
        }

        List<KeyPair> pairs = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            // A null password provider matches FileKeyPairProvider's default: temporary keys have
            // never supported a passphrase.
            Iterable<KeyPair> parsed = SecurityUtils.loadKeyPairIdentities(session, RESOURCE, in, null);
            if (parsed != null) {
                for (KeyPair pair : parsed) {
                    if (pair != null && pair.getPrivate() != null && pair.getPublic() != null) {
                        pairs.add(pair);
                    }
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // Deliberately not chained: a parser message may quote part of the key text, and the
            // callers log the exception with its causes.
            throw new IOException("Could not parse temporary SSH key (" + e.getClass().getSimpleName() + ")");
        }
        if (pairs.isEmpty()) {
            throw new IOException("Could not parse temporary SSH key: no key pairs found");
        }
        return List.copyOf(pairs);
    }
}
