package de.kortty.ui;

import de.kortty.shellintegration.ShellIntegrationInjection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The <b>Shell integration</b> row on the connection editor's <i>Connection</i> tab: whether
 * <b>Add shell integration automatically</b> can be ticked, and which line explains it below. Only the
 * decision lives here, so it is unit-tested without the JavaFX toolkit; {@code ConnectionEditDialog}
 * lays out the controls.
 *
 * <p>The box is for local shell connections that run bash, zsh or fish. It stays tickable while
 * <i>Settings → Terminal → Shell integration</i> is off, so the choice can be made ahead, and the line
 * then says that nothing is added until the setting is on again. korTTY never adds it to an SSH or
 * Mosh session, nor to a connection shared through Teamwork.</p>
 */
final class LocalShellIntegrationOption {

    /** Why the box can or cannot be ticked, in the order the reasons are checked. */
    enum State {
        /** An SSH or Mosh connection: korTTY never adds anything to a remote session. */
        NOT_LOCAL("connEdit.shellIntegration.autoInject.localOnly"),
        /** Shared through Teamwork: its shell always starts as configured. */
        TEAMWORK("connEdit.shellIntegration.autoInject.teamwork"),
        /** Not bash, zsh or fish (on Windows also: not named with its full path). */
        OTHER_SHELL("connEdit.shellIntegration.autoInject.otherShell"),
        /** bash, zsh or fish, started with arguments that leave the startup to the user. */
        OTHER_ARGUMENTS("connEdit.shellIntegration.autoInject.otherArguments"),
        /** Possible, but shell integration is switched off in Settings → Terminal. */
        SWITCHED_OFF("connEdit.shellIntegration.autoInject.switchedOff"),
        /** Possible and in effect when ticked. */
        AVAILABLE("connEdit.shellIntegration.autoInject.hint");

        private final String hintKey;

        State(String hintKey) {
            this.hintKey = hintKey;
        }

        /** The i18n key of the line under the box. */
        @NotNull String hintKey() {
            return hintKey;
        }

        /** Whether the box can be ticked. */
        boolean editable() {
            return this == AVAILABLE || this == SWITCHED_OFF;
        }
    }

    private LocalShellIntegrationOption() {
    }

    /**
     * @param localShell whether the editor's protocol is <i>Local Shell</i>
     * @param teamwork whether the connection comes from a Teamwork source
     * @param support what the selected shell command allows, see
     *     {@code LocalShellTtyConnector.shellIntegrationSupport}; null when unknown
     * @param shellIntegrationEnabled <i>Settings → Terminal → Shell integration</i>
     */
    static @NotNull State state(boolean localShell, boolean teamwork,
                                @Nullable ShellIntegrationInjection.Support support,
                                boolean shellIntegrationEnabled) {
        if (!localShell) {
            return State.NOT_LOCAL;
        }
        if (teamwork) {
            return State.TEAMWORK;
        }
        if (support == null || support == ShellIntegrationInjection.Support.OTHER_SHELL) {
            return State.OTHER_SHELL;
        }
        if (support == ShellIntegrationInjection.Support.OTHER_ARGUMENTS) {
            return State.OTHER_ARGUMENTS;
        }
        return shellIntegrationEnabled ? State.AVAILABLE : State.SWITCHED_OFF;
    }
}
