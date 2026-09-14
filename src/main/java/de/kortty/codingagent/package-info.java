/**
 * Detection of third-party coding agents (Claude Code, Codex, Gemini CLI) running inside local
 * shell terminal panes, and their lifecycle state (working, blocked, done, idle, unknown).
 *
 * <p>"Coding agent" here always means an external CLI agent that the user started in a pane — it is
 * <em>not</em> korTTY's own AI Agent ({@code de.kortty.core.TerminalAgentService}), which is a
 * korTTY-driven agentic loop with its own activity panel.
 *
 * <p>Detection combines two pieces of evidence: the agent's OS process below the pane's shell
 * ({@link de.kortty.codingagent.LocalProcessInspector}) and the live screen contents
 * ({@link de.kortty.codingagent.ScreenSnapshot}) classified by JSON rule files
 * ({@link de.kortty.codingagent.AgentRuleRepository}). Everything in this package is JavaFX-free
 * except {@code TerminalScreenCapture}, which only reads the SithTermFX widget's text buffer.
 * Evaluation runs on a background scheduler; state changes are published through an injected
 * {@link java.util.concurrent.Executor} so the UI receives them on the JavaFX thread while tests
 * run synchronously.
 *
 * <p>On top of detection, the package keeps the UI-facing model of every detected agent — the
 * {@link de.kortty.codingagent.CodingAgentRegistry} with its done-until-seen rule, per-tab rollups
 * and summary — plus the verbs ({@link de.kortty.codingagent.CodingAgentActions}), the
 * cross-window navigation ({@link de.kortty.codingagent.CodingAgentNavigator}) and the desktop
 * notification policy ({@link de.kortty.codingagent.CodingAgentNotificationCoordinator}). These
 * talk to the windows only through small ports ({@link de.kortty.codingagent.FocusOracle},
 * {@link de.kortty.codingagent.PaneLocator}, {@link de.kortty.codingagent.PaneAccess}) that
 * {@code de.kortty.ui.CodingAgentUiBridge} implements; the app-icon badge and desktop notifier
 * backends live in {@link de.kortty.codingagent.desktop}.
 */
package de.kortty.codingagent;
