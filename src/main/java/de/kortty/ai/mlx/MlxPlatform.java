package de.kortty.ai.mlx;

import java.util.Locale;

/** MLX runs exclusively on Apple-Silicon macOS; every MLX surface gates on this check. */
public final class MlxPlatform {

    private MlxPlatform() {
    }

    public static boolean isSupported() {
        return isSupported(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static boolean isSupported(String osName, String osArch) {
        String os = osName != null ? osName.toLowerCase(Locale.ROOT) : "";
        String arch = osArch != null ? osArch.toLowerCase(Locale.ROOT) : "";
        return os.contains("mac") && ("aarch64".equals(arch) || "arm64".equals(arch));
    }
}
