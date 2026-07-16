package de.kortty.model;

/**
 * Controls how separately installed, signed llama.cpp runtime packs are updated.
 */
public enum LlamaRuntimeUpdatePolicy {
    OFF,
    NOTIFY,
    AUTOMATIC_STABLE
}
