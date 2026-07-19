package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Optional;
import java.util.Properties;

/** Immutable release channel, baseline and Ed25519 trust-root configuration. */
public record LlamaRuntimeReleaseConfiguration(
    String baselineRuntimeId,
    String baselineTag,
    String baselineCommit,
    int apiContractVersion,
    URI stableIndexUri,
    URI stableSignatureUri,
    String ed25519PublicKey
) {

    static final String RESOURCE = "/de/kortty/ai/runtimeupdate/llama-runtime-release.properties";
    private static final ReleaseMetadata GENERATED_RELEASE = loadGeneratedRelease();

    /** Compatibility aliases backed by the generated build resource, never duplicated Java literals. */
    public static final String BASELINE_RUNTIME_ID = GENERATED_RELEASE.baselineRuntimeId();
    public static final String BASELINE_TAG = GENERATED_RELEASE.baselineTag();
    public static final String BASELINE_COMMIT = GENERATED_RELEASE.baselineCommit();
    public static final int API_CONTRACT_VERSION = GENERATED_RELEASE.apiContractVersion();
    public static final URI STABLE_INDEX_URI = GENERATED_RELEASE.stableIndexUri();
    public static final URI STABLE_SIGNATURE_URI = GENERATED_RELEASE.stableSignatureUri();

    public LlamaRuntimeReleaseConfiguration {
        validateReleaseMetadata(
            baselineRuntimeId,
            baselineTag,
            baselineCommit,
            apiContractVersion,
            stableIndexUri,
            stableSignatureUri);
        if (!BASELINE_RUNTIME_ID.equals(baselineRuntimeId)
            || !BASELINE_TAG.equals(baselineTag)
            || !BASELINE_COMMIT.equalsIgnoreCase(baselineCommit)
            || apiContractVersion != API_CONTRACT_VERSION) {
            throw new IllegalArgumentException("llama.cpp runtime baseline metadata does not match this korTTY build.");
        }
        if (!STABLE_INDEX_URI.equals(stableIndexUri) || !STABLE_SIGNATURE_URI.equals(stableSignatureUri)) {
            throw new IllegalArgumentException("llama.cpp stable release URLs do not match the pinned korTTY channel.");
        }
        ed25519PublicKey = normalizeKey(ed25519PublicKey);
    }

    public static LlamaRuntimeReleaseConfiguration loadDefault() {
        return new LlamaRuntimeReleaseConfiguration(
            GENERATED_RELEASE.baselineRuntimeId(),
            GENERATED_RELEASE.baselineTag(),
            GENERATED_RELEASE.baselineCommit(),
            GENERATED_RELEASE.apiContractVersion(),
            GENERATED_RELEASE.stableIndexUri(),
            GENERATED_RELEASE.stableSignatureUri(),
            GENERATED_RELEASE.ed25519PublicKey());
    }

    public Optional<String> configuredPublicKey() {
        return Optional.ofNullable(ed25519PublicKey);
    }

    /** Returns the trusted Ed25519 key or fails closed before any network request is made. */
    public PublicKey requireTrustedPublicKey() throws IOException {
        if (ed25519PublicKey == null) {
            throw new IOException(
                "No llama.cpp runtime release trust root is embedded in this korTTY build.");
        }
        try {
            return LlamaRuntimeIndexVerifier.decodePublicKey(ed25519PublicKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("The configured llama.cpp runtime Ed25519 public key is invalid.", e);
        }
    }

    private static String normalizeKey(String value) {
        String normalized = value != null && !value.isBlank() ? value.trim() : null;
        if (normalized != null && normalized.toUpperCase(java.util.Locale.ROOT).contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("A private key must never be configured as a runtime trust root.");
        }
        return normalized;
    }

    private static void validateReleaseMetadata(
        String runtimeId,
        String tag,
        String commit,
        int contractVersion,
        URI indexUri,
        URI signatureUri
    ) {
        if (runtimeId == null || !runtimeId.matches("llama-b[0-9]+-kortty[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid llama.cpp runtime baseline id.");
        }
        if (tag == null || !tag.matches("b[0-9]+")
            || !runtimeId.startsWith("llama-" + tag + "-kortty")) {
            throw new IllegalArgumentException("Invalid llama.cpp runtime baseline tag.");
        }
        if (commit == null || !commit.matches("(?i)[0-9a-f]{40}")) {
            throw new IllegalArgumentException("llama.cpp runtime baseline commit must be a full SHA-1.");
        }
        if (contractVersion < 1) {
            throw new IllegalArgumentException("llama.cpp runtime API contract version must be positive.");
        }
        if (indexUri == null || signatureUri == null
            || !"https".equalsIgnoreCase(indexUri.getScheme())
            || !"https".equalsIgnoreCase(signatureUri.getScheme())) {
            throw new IllegalArgumentException("llama.cpp stable release URLs must use HTTPS.");
        }
    }

    private static ReleaseMetadata loadGeneratedRelease() {
        Properties properties = new Properties();
        try (InputStream input = LlamaRuntimeReleaseConfiguration.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                    "The generated llama.cpp runtime release configuration is missing from this korTTY build.");
            }
            properties.load(input);
            return new ReleaseMetadata(
                required(properties, "baseline.runtimeId"),
                required(properties, "baseline.tag"),
                required(properties, "baseline.commit"),
                integer(required(properties, "baseline.apiContractVersion")),
                uri(required(properties, "stable.indexUrl"), "stable.indexUrl"),
                uri(required(properties, "stable.signatureUrl"), "stable.signatureUrl"),
                properties.getProperty("trust.ed25519PublicKey"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the llama.cpp runtime release configuration.", e);
        }
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "The generated llama.cpp runtime release configuration is missing " + name + ".");
        }
        return value.trim();
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid generated llama.cpp runtime API contract version.", e);
        }
    }

    private static URI uri(String value, String name) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "Invalid generated llama.cpp runtime release URI for " + name + ".", e);
        }
    }

    private record ReleaseMetadata(
        String baselineRuntimeId,
        String baselineTag,
        String baselineCommit,
        int apiContractVersion,
        URI stableIndexUri,
        URI stableSignatureUri,
        String ed25519PublicKey
    ) {
        private ReleaseMetadata {
            validateReleaseMetadata(
                baselineRuntimeId,
                baselineTag,
                baselineCommit,
                apiContractVersion,
                stableIndexUri,
                stableSignatureUri);
            ed25519PublicKey = normalizeKey(ed25519PublicKey);
        }
    }
}
