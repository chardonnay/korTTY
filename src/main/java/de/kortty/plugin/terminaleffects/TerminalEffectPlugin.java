package de.kortty.plugin.terminaleffects;

/**
 * Service Provider Interface for trusted Java terminal effect plugins.
 *
 * <p>Implementations are loaded through {@link java.util.ServiceLoader}. External plugins run
 * inside the KorTTY JVM and must therefore be treated as trusted local code.</p>
 */
public interface TerminalEffectPlugin {

    /**
     * Stable plugin identifier used for persistence.
     */
    String id();

    /**
     * Human-readable name shown in the UI.
     */
    String displayName();

    /**
     * Short human-readable description shown in plugin management.
     */
    default String description() {
        return "";
    }

    /**
     * Creates a new effect session for one terminal tab.
     */
    TerminalEffectSession createSession(TerminalEffectContext context);
}
