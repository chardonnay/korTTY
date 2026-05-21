package de.kortty.update;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record UpdateRelease(
    String tagName,
    String name,
    URI htmlUri,
    Instant publishedAt,
    boolean draft,
    boolean prerelease,
    List<UpdateAsset> assets
) {

    public UpdateRelease {
        tagName = Objects.requireNonNull(tagName, "tagName").trim();
        name = name != null ? name.trim() : "";
        assets = assets == null ? List.of() : List.copyOf(assets);
        if (tagName.isBlank()) {
            throw new IllegalArgumentException("Release tag must not be blank.");
        }
    }

    public boolean isStableLatestRelease() {
        return !draft && !prerelease;
    }
}
