package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Opt-in JVM memory/GC profile for the packaged application. The chosen profile is applied by
 * relaunching the app once at startup with overriding {@code _JAVA_OPTIONS} (see
 * {@code de.kortty.JvmRelauncher}); it only takes effect in the jpackage build and requires a
 * restart. The default (BALANCED) never relaunches — it uses the heap/GC options baked into the
 * launcher configuration.
 *
 * <p>These are curated profiles rather than free-form flags on purpose: a single unrecognized
 * {@code -XX} option aborts JVM startup, which would make the packaged app unlaunchable.</p>
 */
@XmlEnum
public enum JvmResourceProfile {

    /** The shipped default: hard 2 GB heap cap, G1 with periodic idle uncommit. No relaunch. */
    @XmlEnumValue("BALANCED") BALANCED,

    /** Larger heap (~50% of physical RAM), still G1 with idle uncommit. */
    @XmlEnumValue("HIGH") HIGH,

    /** Near-unbounded heap (~75% of physical RAM) with the low-pause Z Garbage Collector. */
    @XmlEnumValue("MAXIMUM") MAXIMUM;

    /** Never let the override heap drop below the shipped 2 GB default. */
    private static final long MIN_HEAP_MB = 2048;

    /** Fallback heaps (MB) used only when physical RAM cannot be detected. */
    private static final long HIGH_FALLBACK_MB = 4096;
    private static final long MAXIMUM_FALLBACK_MB = 6144;

    /** i18n suffix, e.g. {@code settings.resources.profile.balanced}. */
    public String i18nKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Resolves the {@code _JAVA_OPTIONS} string that overrides the baked-in launcher defaults for
     * this profile, or {@code null} when no relaunch is needed (BALANCED). The heap size is sized
     * from {@code totalPhysicalMemoryBytes}; pass {@code <= 0} to use fixed fallbacks.
     */
    public String resolveJavaOptions(long totalPhysicalMemoryBytes) {
        return switch (this) {
            case BALANCED -> null;
            case HIGH -> "-Xmx" + maxHeapMegabytes(totalPhysicalMemoryBytes) + "m";
            // ZGC is added on top of the baked options; G1 is not baked explicitly, so there is no
            // collector conflict, and the remaining G1-only flags are inert (not fatal) under ZGC.
            case MAXIMUM -> "-XX:+UseZGC -Xmx" + maxHeapMegabytes(totalPhysicalMemoryBytes) + "m";
        };
    }

    /**
     * The maximum Java heap (in MB) this profile allows on a machine with the given physical RAM.
     * BALANCED reports the shipped fixed 2 GB cap even though it does not relaunch, so callers can
     * display a consistent ceiling for every profile. Pass {@code <= 0} to use fixed fallbacks.
     */
    public long maxHeapMegabytes(long totalPhysicalMemoryBytes) {
        return switch (this) {
            case BALANCED -> MIN_HEAP_MB;
            case HIGH -> heapMb(totalPhysicalMemoryBytes, 50, HIGH_FALLBACK_MB);
            case MAXIMUM -> heapMb(totalPhysicalMemoryBytes, 75, MAXIMUM_FALLBACK_MB);
        };
    }

    private static long heapMb(long totalBytes, long percent, long fallbackMb) {
        if (totalBytes <= 0) {
            return Math.max(MIN_HEAP_MB, fallbackMb);
        }
        long mb = (totalBytes / (1024 * 1024)) * percent / 100;
        return Math.max(MIN_HEAP_MB, mb);
    }

    /** Parses a persisted name leniently, falling back to {@link #BALANCED}. */
    public static JvmResourceProfile fromName(String name) {
        if (name == null) {
            return BALANCED;
        }
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BALANCED;
        }
    }
}
