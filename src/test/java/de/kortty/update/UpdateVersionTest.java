package de.kortty.update;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class UpdateVersionTest {

    @Test
    void comparesSemanticVersionsNumerically() {
        UpdateVersion older = UpdateVersion.parse("v2.2.0").orElseThrow();
        UpdateVersion newer = UpdateVersion.parse("v2.10.0").orElseThrow();

        assertThat(newer.compareTo(older)).isGreaterThan(0);
    }

    @Test
    void treatsReleaseAsNewerThanPrerelease() {
        UpdateVersion prerelease = UpdateVersion.parse("v2.2.0-beta.1").orElseThrow();
        UpdateVersion release = UpdateVersion.parse("2.2.0").orElseThrow();

        assertThat(release.compareTo(prerelease)).isGreaterThan(0);
    }

    @Test
    void rejectsUnparseableVersions() {
        assertThat(UpdateVersion.parse("release-two")).isEmpty();
    }
}
