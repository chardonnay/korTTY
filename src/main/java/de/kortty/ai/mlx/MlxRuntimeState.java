package de.kortty.ai.mlx;

/** Observable lifecycle of one model-specific MLX sidecar process. */
public enum MlxRuntimeState {
    STOPPED,
    STARTING,
    LOADING,
    READY,
    BUSY,
    SLEEPING,
    FAILED
}
