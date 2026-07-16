package de.kortty.core;

import de.kortty.model.AiWorkload;

/**
 * Declares how a direct system/user prompt is being executed so decorators can apply only the
 * knowledge stores that are safe and relevant for that call.
 */
public enum AiPromptExecutionScope {
    TEXT(AiWorkload.TEXT, false),
    CODING(AiWorkload.CODING, false),
    AUTONOMOUS(null, true);

    private final AiWorkload workload;
    private final boolean autonomous;

    AiPromptExecutionScope(AiWorkload workload, boolean autonomous) {
        this.workload = workload;
        this.autonomous = autonomous;
    }

    public AiWorkload workload() {
        return workload;
    }

    public boolean autonomous() {
        return autonomous;
    }

    static AiPromptExecutionScope normalize(AiPromptExecutionScope scope) {
        return scope != null ? scope : AUTONOMOUS;
    }
}
