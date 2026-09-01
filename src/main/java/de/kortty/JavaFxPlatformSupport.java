package de.kortty;

import java.util.Locale;

/** Early JavaFX platform workarounds that must run before the toolkit is initialized. */
public final class JavaFxPlatformSupport {

    private static final String PRISM_ORDER = "prism.order";

    private JavaFxPlatformSupport() {
    }

    /**
     * Uses JavaFX's software renderer for an x64 JVM emulated on Windows ARM. The Direct3D
     * pipeline can create a window on that combination while failing to paint its scene graph,
     * leaving dialogs completely blank. An explicitly configured Prism pipeline always wins.
     */
    public static void configureRenderer() {
        if (System.getProperty(PRISM_ORDER) != null) {
            return;
        }
        if (requiresSoftwareRenderer(
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getenv("PROCESSOR_IDENTIFIER"),
                System.getenv("PROCESSOR_ARCHITEW6432"))) {
            System.setProperty(PRISM_ORDER, "sw");
        }
    }

    static boolean requiresSoftwareRenderer(
            String osName, String jvmArchitecture, String processorIdentifier,
            String emulationHostArchitecture) {
        return contains(osName, "windows")
                && (contains(jvmArchitecture, "amd64") || contains(jvmArchitecture, "x86_64"))
                && (contains(processorIdentifier, "arm")
                    || contains(processorIdentifier, "aarch64")
                    || contains(emulationHostArchitecture, "arm64")
                    || contains(emulationHostArchitecture, "aarch64"));
    }

    private static boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }
}
