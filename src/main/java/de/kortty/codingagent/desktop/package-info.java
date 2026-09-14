/**
 * Desktop integration for coding-agent detection: the application-icon badge (number of agents
 * waiting for a decision) and desktop notifications for settled state transitions.
 *
 * <p>Both services take only ints and strings, so the package is independent of the registry and
 * the UI. Every operating-system backend sits behind an interface ({@link
 * de.kortty.codingagent.desktop.AppBadgeBackend}, {@link
 * de.kortty.codingagent.desktop.DesktopNotifierBackend}) with an {@code Unsupported} fallback that
 * {@link de.kortty.codingagent.desktop.AppBadgeBackends} and {@link
 * de.kortty.codingagent.desktop.DesktopNotifierBackends} choose from a {@link
 * de.kortty.codingagent.desktop.PlatformProbe} <em>before</em> any backend constructor runs, so a
 * backend class for the wrong operating system is never instantiated.
 *
 * <p>Threading: {@link de.kortty.codingagent.desktop.AppBadgeService#update} and {@code refresh}
 * run on the JavaFX thread (the Windows backend snapshots a canvas); external processes
 * ({@code osascript}, {@code notify-send}) run through {@link
 * de.kortty.codingagent.desktop.ExternalCommandRunner} on named daemon executors, the Linux
 * launcher counter is emitted over the persistent session-bus connection of {@link
 * de.kortty.codingagent.desktop.LauncherEntryDBusConnection} on the {@code kortty-app-badge}
 * executor, and AWT work
 * ({@code Taskbar}, {@code SystemTray}) is always posted with {@code EventQueue.invokeLater} — the
 * JavaFX thread never waits for either. Failures degrade to the {@code Unsupported} backend or the
 * window-title fallback and are logged once per session.
 */
package de.kortty.codingagent.desktop;
