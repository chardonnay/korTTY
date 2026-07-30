package de.kortty.core;

import java.time.Duration;

/**
 * Implemented by AI services whose request timeout is user-configurable.
 *
 * <p>{@link AiServiceFactory} resolves the effective timeout once per profile (see
 * {@link AiRequestTimeoutSupport}) and applies it here, so no implementation invents a limit of
 * its own.
 */
public interface AiRequestTimeoutAware {

    /**
     * @param requestTimeout the configured timeout, or {@code null} when requests must run to
     *     completion without any timeout
     */
    void setRequestTimeout(Duration requestTimeout);
}
