package de.kortty.update;

import java.net.URI;
import java.util.Objects;

public record UpdateAsset(String name, URI downloadUri, long size, String digest) {

    public UpdateAsset {
        name = Objects.requireNonNull(name, "name").trim();
        downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
        digest = digest != null ? digest.trim() : null;
        if (name.isBlank()) {
            throw new IllegalArgumentException("Asset name must not be blank.");
        }
    }
}
