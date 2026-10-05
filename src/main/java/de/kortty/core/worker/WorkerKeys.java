package de.kortty.core.worker;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;

/** Public keys as the control channel carries them, and the signatures korTTY makes for a worker. */
public final class WorkerKeys {

    private WorkerKeys() {
    }

    /** {@code line} in the OpenSSH public-key format ({@code ssh-ed25519 AAAA...}). */
    public static PublicKey parse(String line) throws IOException {
        try {
            return AuthorizedKeyEntry.parseAuthorizedKeyEntry(line)
                .resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("unreadable public key: " + e.getMessage(), e);
        }
    }

    /**
     * Signs {@code data} with {@code pair}'s private key using the SSH signature {@code algorithm}
     * ({@code rsa-sha2-512}, {@code ssh-ed25519}, ...), as an agent would.
     *
     * @return the raw signature blob
     */
    public static byte[] sign(KeyPair pair, String algorithm, byte[] data) throws IOException {
        BuiltinSignatures factory = BuiltinSignatures.fromFactoryName(algorithm);
        if (factory == null || !factory.isSupported()) {
            throw new IOException("unsupported signature algorithm " + algorithm);
        }
        try {
            Signature signature = factory.create();
            signature.initSigner(null, pair.getPrivate());
            signature.update(null, data);
            return signature.sign(null);
        } catch (Exception e) {
            throw new IOException("signing failed: " + e.getMessage(), e);
        }
    }
}
