package de.kortty.update;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateVersion implements Comparable<UpdateVersion> {

    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final String prerelease;

    private UpdateVersion(int major, int minor, int patch, String prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static Optional<UpdateVersion> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            return Optional.of(new UpdateVersion(major, minor, patch, matcher.group(4)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(UpdateVersion other) {
        Objects.requireNonNull(other, "other");
        int majorComparison = Integer.compare(major, other.major);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minor, other.minor);
        if (minorComparison != 0) {
            return minorComparison;
        }
        int patchComparison = Integer.compare(patch, other.patch);
        if (patchComparison != 0) {
            return patchComparison;
        }
        if (prerelease == null && other.prerelease != null) {
            return 1;
        }
        if (prerelease != null && other.prerelease == null) {
            return -1;
        }
        if (prerelease == null) {
            return 0;
        }
        return prerelease.compareTo(other.prerelease);
    }

    @Override
    public String toString() {
        String version = major + "." + minor + "." + patch;
        return prerelease == null ? version : version + "-" + prerelease;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UpdateVersion other)) {
            return false;
        }
        return major == other.major
            && minor == other.minor
            && patch == other.patch
            && Objects.equals(prerelease, other.prerelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prerelease);
    }
}
