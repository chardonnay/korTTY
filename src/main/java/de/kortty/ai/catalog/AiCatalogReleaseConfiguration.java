package de.kortty.ai.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** Fixed independent release channel and optional build-injected Ed25519 catalog trust root. */
public record AiCatalogReleaseConfiguration(
    URI catalogUri,
    URI signatureUri,
    String ed25519PublicKey
) {

    public static final URI CATALOG_URI = URI.create(
        "https://github.com/chardonnay/kortty-ai-catalog/releases/latest/download/model-prompt-catalog-v1.json");
    public static final URI SIGNATURE_URI = URI.create(
        "https://github.com/chardonnay/kortty-ai-catalog/releases/latest/download/model-prompt-catalog-v1.sig");
    public static final String PUBLIC_KEY_PROPERTY = "kortty.aiCatalogPublicKey";
    public static final String PUBLIC_KEY_ENVIRONMENT = "KORTTY_AI_CATALOG_PUBLIC_KEY";
    static final String RESOURCE = "/de/kortty/ai/catalog/ai-catalog-release.properties";

    public AiCatalogReleaseConfiguration {
        catalogUri = Objects.requireNonNull(catalogUri, "catalogUri");
        signatureUri = Objects.requireNonNull(signatureUri, "signatureUri");
        if (!CATALOG_URI.equals(catalogUri) || !SIGNATURE_URI.equals(signatureUri)) {
            throw new IllegalArgumentException("AI catalog URLs do not match the pinned independent stable channel.");
        }
        ed25519PublicKey = normalizeKey(ed25519PublicKey);
    }

    public static AiCatalogReleaseConfiguration loadDefault() {
        Properties properties = new Properties();
        try (InputStream input = AiCatalogReleaseConfiguration.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the AI catalog release configuration.", e);
        }
        String key = firstNonBlank(
            System.getProperty(PUBLIC_KEY_PROPERTY),
            System.getenv(PUBLIC_KEY_ENVIRONMENT),
            properties.getProperty("trust.ed25519PublicKey"));
        return new AiCatalogReleaseConfiguration(
            URI.create(properties.getProperty("stable.catalogUrl", CATALOG_URI.toString())),
            URI.create(properties.getProperty("stable.signatureUrl", SIGNATURE_URI.toString())),
            key);
    }

    public Optional<String> configuredPublicKey() {
        return Optional.ofNullable(ed25519PublicKey);
    }

    public Optional<PublicKey> trustedPublicKey() throws GeneralSecurityException {
        if (ed25519PublicKey == null) {
            return Optional.empty();
        }
        return Optional.of(AiCatalogSignatureVerifier.decodePublicKey(ed25519PublicKey));
    }

    private static String normalizeKey(String value) {
        String normalized = value != null && !value.isBlank() ? value.trim() : null;
        if (normalized != null && normalized.toUpperCase(java.util.Locale.ROOT).contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("A private key must never be configured as an AI catalog trust root.");
        }
        return normalized;
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
