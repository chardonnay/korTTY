/**
 * The local control API: korTTY's newline-delimited JSON-RPC surface for scripts and coding agents
 * that run on the same machine as the user.
 *
 * <p>This package is deliberately free of JavaFX, of {@code java.net} and of {@code java.nio.channels}
 * in its contract layer: every value type, every error, the framing, the key vocabulary and the
 * method table are pure and unit-testable without a toolkit. The two places where the API has to
 * touch the windows are narrow ports — {@link de.kortty.control.ControlSurface} and
 * {@link de.kortty.control.UiDispatcher} — implemented by {@code de.kortty.ui.ControlApiUiBridge},
 * exactly as {@code de.kortty.codingagent} talks to the UI through
 * {@code FocusOracle}/{@code PaneLocator}/{@code PaneAccess}.
 *
 * <p>Thread contract: every type here is documented individually. As a rule the value types, the
 * codecs and the tables are pure and callable from any thread; {@link de.kortty.control.ControlSurface}
 * is JavaFX-application-thread only except where a method's javadoc says ANY THREAD. No class in this
 * package may call {@code javafx.application.Platform} directly — marshalling goes through
 * {@link de.kortty.control.UiDispatcher} and {@link de.kortty.control.UiCalls}.
 */
package de.kortty.control;
