package de.kortty.control;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pure enabled-state gate of the control API: the single source of truth, evaluated at start-up,
 * from the Settings post-save hook, on every {@code accept()} and again on every dispatch.
 *
 * <p>Pure, any thread.
 *
 * <p>The API runs only when <strong>both</strong> legs say yes — enterprise policy allows the
 * {@code control-api} feature and the user has ticked the (default-off) setting. Every unknown leg is
 * treated as a refusal, so a half-wired build never opens a listener.
 */
public final class ControlApiGate {

    private static final Logger LOG = LoggerFactory.getLogger(ControlApiGate.class);

    /** The {@link EffectivePolicy} accessor that decides the policy leg. */
    private static final String POLICY_ACCESSOR = "controlApiAllowed";

    /** The {@link GlobalSettings} accessor that decides the setting leg. */
    private static final String SETTINGS_ACCESSOR = "isControlApiEnabled";

    private ControlApiGate() {
    }

    /**
     * Whether the control-API server should be listening right now.
     *
     * <p>A null argument, or an accessor this build does not carry yet, is a refusal: the gate is
     * fail-closed in both legs.
     *
     * @param settings the live global settings, or null before they are loaded
     * @param policy the resolved enterprise policy, or null before it is resolved
     */
    public static boolean shouldRun(GlobalSettings settings, EffectivePolicy policy) {
        return evaluate(flag(policy, POLICY_ACCESSOR), flag(settings, SETTINGS_ACCESSOR));
    }

    /**
     * The truth table itself, free of reflection so it can be exercised exhaustively.
     *
     * @param policyAllowed the policy leg; null when it could not be determined
     * @param settingsEnabled the setting leg; null when it could not be determined
     */
    static boolean evaluate(Boolean policyAllowed, Boolean settingsEnabled) {
        return Boolean.TRUE.equals(policyAllowed) && Boolean.TRUE.equals(settingsEnabled);
    }

    /**
     * Reads a no-argument boolean accessor off {@code target}'s runtime class.
     *
     * <p>Resolved reflectively, and against the <em>runtime</em> class rather than the declared one,
     * so this package compiles and is unit-testable before the integration package adds
     * {@code GlobalSettings.isControlApiEnabled()} and {@code EffectivePolicy.controlApiAllowed()}.
     *
     * @return the flag, or null when the target is null or carries no such accessor
     */
    static Boolean flag(Object target, String accessor) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(accessor);
            if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
                return null;
            }
            Object value = method.invoke(target);
            return value instanceof Boolean flag ? flag : null;
        } catch (NoSuchMethodException e) {
            LOG.debug("control-api gate: {} has no {}(), treating the leg as denied",
                target.getClass().getName(), accessor);
            return null;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            LOG.warn("control-api gate: {}() could not be read, treating the leg as denied", accessor, e);
            return null;
        }
    }
}
