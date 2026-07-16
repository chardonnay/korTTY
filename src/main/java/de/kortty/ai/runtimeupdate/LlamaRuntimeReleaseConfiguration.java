package de.kortty.ai.runtimeupdate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Objects;
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

    public static final String BASELINE_RUNTIME_ID = "llama-b10025-kortty1";
    public static final String BASELINE_TAG = "b10025";
    public static final String BASELINE_COMMIT = "a3e5b96ac5e278c390df429df0b68efcee3ee1b5";
    public static final int API_CONTRACT_VERSION = 1;
    public static final URI STABLE_INDEX_URI = URI.create(
        "https://github.com/chardonnay/kortty-llama-runtimes/releases/latest/download/runtime-index-v1.json");
    public static final URI STABLE_SIGNATURE_URI = URI.create(
        "https://github.com/chardonnay/kortty-llama-runtimes/releases/latest/download/runtime-index-v1.sig");
    static final String RESOURCE = "/de/kortty/ai/runtimeupdate/llama-runtime-release.properties";

    public LlamaRuntimeReleaseConfiguration {
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
        Properties properties = new Properties();
        try (InputStream input = LlamaRuntimeReleaseConfiguration.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the llama.cpp runtime release configuration.", e);
        }
        String key = firstNonBlank(
            System.getProperty("kortty.llamaRuntimePublicKey"),
            System.getenv("KORTTY_LLAMA_RUNTIME_PUBLIC_KEY"),
            properties.getProperty("trust.ed25519PublicKey"));
        return new LlamaRuntimeReleaseConfiguration(
            properties.getProperty("baseline.runtimeId", BASELINE_RUNTIME_ID),
            properties.getProperty("baseline.tag", BASELINE_TAG),
            properties.getProperty("baseline.commit", BASELINE_COMMIT),
            integer(properties.getProperty("baseline.apiContractVersion"), API_CONTRACT_VERSION),
            URI.create(properties.getProperty("stable.indexUrl", STABLE_INDEX_URI.toString())),
            URI.create(properties.getProperty("stable.signatureUrl", STABLE_SIGNATURE_URI.toString())),
            key);
    }

    public Optional<String> configuredPublicKey() {
        return Optional.ofNullable(ed25519PublicKey);
    }

    /** Returns the trusted Ed25519 key or fails closed before any network request is made. */
    public PublicKey requireTrustedPublicKey() throws IOException {
        if (ed25519PublicKey == null) {
            throw new IOException(
                "No llama.cpp runtime release trust root is configured. "
                    + "Set the Gradle property kortty.llamaRuntimePublicKey or "
                    + "KORTTY_LLAMA_RUNTIME_PUBLIC_KEY when building a release.");
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

    private static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid llama.cpp runtime API contract version.", e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : Objects.requireNonNull(values, "values")) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
