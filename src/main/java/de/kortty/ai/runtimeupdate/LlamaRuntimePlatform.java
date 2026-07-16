package de.kortty.ai.runtimeupdate;

import java.util.Locale;

public enum LlamaRuntimePlatform {
    MACOS,
    WINDOWS,
    LINUX;

    public static LlamaRuntimePlatform current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac")) {
            return MACOS;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("linux")) {
            return LINUX;
        }
        throw new IllegalStateException("Unsupported llama.cpp runtime platform: " + name);
    }

    public String manifestValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
