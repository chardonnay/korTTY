package de.kortty.control;

/**
 * Identity of this running korTTY. A fresh UUID is minted per process start, so a client that sees a
 * different {@code instanceId} knows every id it holds is stale and re-enumerates.
 *
 * <p>Pure, any thread.
 *
 * @param instanceId a UUID minted at server start
 * @param appVersion the korTTY version string
 * @param protocolVersion always {@link ControlApiProtocol#PROTOCOL_VERSION}
 * @param startedAtMillis wall-clock millis at server start
 */
public record ControlInstance(String instanceId, String appVersion, int protocolVersion,
                              long startedAtMillis) {
}
