package de.kortty.ai.llama;

/** Observable lifecycle of one model-specific llama-server process. */
public enum LlamaRuntimeState {
    STOPPED,
    STARTING,
    LOADING,
    READY,
    BUSY,
    SLEEPING,
    FAILED
}
