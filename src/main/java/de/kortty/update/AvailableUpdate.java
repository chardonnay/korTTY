package de.kortty.update;

import java.util.Objects;

public record AvailableUpdate(
    UpdateRelease release,
    UpdateAsset asset,
    UpdateVersion latestVersion,
    UpdateVersion currentVersion
) {

    public AvailableUpdate {
        release = Objects.requireNonNull(release, "release");
        asset = Objects.requireNonNull(asset, "asset");
        latestVersion = Objects.requireNonNull(latestVersion, "latestVersion");
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
    }

    public String versionLabel() {
        return release.tagName();
    }
}
