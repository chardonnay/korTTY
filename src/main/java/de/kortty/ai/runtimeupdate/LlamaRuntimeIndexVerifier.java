package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** Verifies the exact downloaded JSON bytes using Ed25519 before parsing any package URLs. */
public final class LlamaRuntimeIndexVerifier {

    private static final int MAX_INDEX_BYTES = 5 * 1024 * 1024;

    private final PublicKey publicKey;
    private final LlamaRuntimeIndexCodec codec;

    public LlamaRuntimeIndexVerifier(PublicKey publicKey) {
        this(publicKey, new LlamaRuntimeIndexCodec());
    }

    public LlamaRuntimeIndexVerifier(PublicKey publicKey, LlamaRuntimeIndexCodec codec) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        if (!"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
            && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("Runtime index key must be Ed25519.");
        }
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public LlamaRuntimeIndex verifyAndParse(byte[] indexBytes, String detachedSignature) throws IOException {
        if (indexBytes == null || indexBytes.length == 0 || indexBytes.length > MAX_INDEX_BYTES) {
            throw new IOException("Runtime index has an invalid size.");
        }
        byte[] signatureBytes = decodeSignature(detachedSignature);
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(indexBytes);
            if (!verifier.verify(signatureBytes)) {
                throw new IOException("Runtime index signature verification failed.");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 runtime index verification is unavailable.", e);
        }
        return codec.parse(indexBytes);
    }

    public static PublicKey decodePublicKey(String pemOrBase64) throws GeneralSecurityException {
        if (pemOrBase64 == null || pemOrBase64.isBlank()) {
            throw new IllegalArgumentException("Ed25519 public key is required.");
        }
        String encoded = pemOrBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    private static byte[] decodeSignature(String detachedSignature) throws IOException {
        if (detachedSignature == null || detachedSignature.isBlank()) {
            throw new IOException("Runtime index has no detached signature.");
        }
        String normalized = detachedSignature.trim();
        if (normalized.startsWith("ed25519:")) {
            normalized = normalized.substring("ed25519:".length()).trim();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(normalized);
            if (decoded.length != 64) {
                throw new IOException("Runtime index Ed25519 signature has an invalid length.");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("Runtime index signature is not valid Base64.", e);
        }
    }
}
