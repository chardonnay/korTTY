package de.kortty.ai.llama;

/** Hardware backend selected for an installed llama.cpp runtime pack. */
public enum LlamaBackend {
    AUTO,
    CPU,
    METAL,
    VULKAN
}
