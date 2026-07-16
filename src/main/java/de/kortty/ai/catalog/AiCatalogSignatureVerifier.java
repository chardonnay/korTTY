package de.kortty.ai.catalog;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** Verifies exact catalog bytes with a dedicated Ed25519 trust root before schema parsing. */
public final class AiCatalogSignatureVerifier {

    public static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    private final PublicKey publicKey;
    private final AiCatalogCodec codec;

    public AiCatalogSignatureVerifier(PublicKey publicKey) {
        this(publicKey, new AiCatalogCodec());
    }

    public AiCatalogSignatureVerifier(PublicKey publicKey, AiCatalogCodec codec) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        if (!"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
            && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("AI catalog trust root must be Ed25519.");
        }
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public AiModelPromptCatalog verifyAndParse(AiCatalogSource.SignedPayload payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        byte[] catalogBytes = payload.catalogBytes();
        if (catalogBytes.length == 0 || catalogBytes.length > MAX_CATALOG_BYTES) {
            throw new IOException("AI catalog has an invalid size.");
        }
        byte[] signatureBytes = decodeSignature(payload.detachedSignature());
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(catalogBytes);
            if (!verifier.verify(signatureBytes)) {
                throw new IOException("AI catalog signature verification failed.");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 AI catalog verification is unavailable.", e);
        }
        return codec.parse(catalogBytes);
    }

    public static PublicKey decodePublicKey(String pemOrBase64) throws GeneralSecurityException {
        if (pemOrBase64 == null || pemOrBase64.isBlank()) {
            throw new IllegalArgumentException("AI catalog Ed25519 public key is required.");
        }
        String encoded = pemOrBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
        if (!"EdDSA".equalsIgnoreCase(key.getAlgorithm()) && !"Ed25519".equalsIgnoreCase(key.getAlgorithm())) {
            throw new GeneralSecurityException("AI catalog public key is not Ed25519.");
        }
        return key;
    }

    private static byte[] decodeSignature(String detachedSignature) throws IOException {
        String normalized = detachedSignature != null ? detachedSignature.trim() : "";
        if (normalized.startsWith("ed25519:")) {
            normalized = normalized.substring("ed25519:".length()).trim();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(normalized);
            if (decoded.length != 64) {
                throw new IOException("AI catalog Ed25519 signature has an invalid length.");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("AI catalog signature is not valid Base64.", e);
        }
    }
}
