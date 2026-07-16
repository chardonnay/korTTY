package de.kortty.ai.llama;

/** Determines whether a sidecar exposes chat generation or the dedicated embedding API. */
public enum LlamaModelPurpose {
    CHAT,
    EMBEDDING
}
