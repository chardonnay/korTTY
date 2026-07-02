package de.kortty.ui;

import de.kortty.core.LocalShellTtyConnector;
import de.kortty.model.ServerConnection;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the "Shell" preset list for the local-shell (LOCAL_SHELL) protocol, shared by
 * {@link QuickConnectDialog} and {@link ConnectionEditDialog} so the two stay in sync.
 *
 * <p>Only shells that actually exist on the host OS are offered — a Linux box never sees
 * PowerShell or cmd.exe, and a Windows box never sees the Korn shell:
 * <ul>
 *   <li>Windows → PowerShell, cmd.exe, plus detected Git Bash / Cygwin / WSL</li>
 *   <li>macOS → Zsh, Bash</li>
 *   <li>Linux (and other unrecognised Unix) → Bash</li>
 *   <li>Solaris (SunOS) → Korn shell</li>
 * </ul>
 * "Custom command…" is always offered last so any other shell can still be entered by hand.
 */
final class LocalShellPresetSupport {

    /** Preset whose id IS the launch command (resolved via PATH at spawn time). */
    static final String POWERSHELL = "powershell.exe";
    static final String CMD = "cmd.exe";
    static final String ZSH = "zsh";
    static final String BASH = "bash";
    static final String KSH = "ksh";
    /** Sentinel presets whose real command is resolved from a detected install location. */
    static final String GIT_BASH = "__gitbash__";
    static final String CYGWIN = "__cygwin__";
    static final String WSL = "__wsl__";
    static final String CUSTOM = "__custom__";

    private LocalShellPresetSupport() {
    }

    /**
     * Ordered preset ids appropriate for the current OS. {@code gitBashCommand}/{@code cygwinCommand}/
     * {@code wslCommand} are the resolved launch commands (or {@code null} when not installed); they
     * only ever apply on Windows. The list always ends with {@link #CUSTOM}.
     */
    static List<String> presetsForCurrentOs(String gitBashCommand, String cygwinCommand, String wslCommand) {
        List<String> presets = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (LocalShellTtyConnector.isWindows()) {
            presets.add(POWERSHELL);
            presets.add(CMD);
            if (gitBashCommand != null) {
                presets.add(GIT_BASH);
            }
            if (cygwinCommand != null) {
                presets.add(CYGWIN);
            }
            if (wslCommand != null) {
                presets.add(WSL);
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            presets.add(ZSH);
            presets.add(BASH);
        } else if (os.contains("sunos") || os.contains("solaris")) {
            presets.add(KSH);
        } else {
            // Linux and any other Unix-like: Bash is the safe, universally-present default.
            presets.add(BASH);
        }
        presets.add(CUSTOM);
        return presets;
    }

    /** Fills {@code combo} with the OS-appropriate presets, wires the label renderer, and selects the default. */
    static void configure(ComboBox<String> combo, String gitBashCommand, String cygwinCommand, String wslCommand) {
        combo.getItems().setAll(presetsForCurrentOs(gitBashCommand, cygwinCommand, wslCommand));
        combo.setButtonCell(listCell());
        combo.setCellFactory(lv -> listCell());
        combo.setValue(combo.getItems().get(0));
    }

    /** A list cell that renders a preset id as its localized display label. */
    static ListCell<String> listCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label(item));
            }
        };
    }

    /** The localized display label for a preset id. */
    static String label(String preset) {
        if (preset == null) {
            return null;
        }
        switch (preset) {
            case CUSTOM:
                return I18n.get("connEdit.shell.custom");
            case POWERSHELL:
                return I18n.get("connEdit.shell.powershell");
            case CMD:
                return I18n.get("connEdit.shell.cmd");
            case GIT_BASH:
                return I18n.get("connEdit.shell.gitbash");
            case CYGWIN:
                return I18n.get("connEdit.shell.cygwin");
            case WSL:
                return I18n.get("connEdit.shell.wsl");
            case ZSH:
                return I18n.get("connEdit.shell.zsh");
            case BASH:
                return I18n.get("connEdit.shell.bash");
            case KSH:
                return I18n.get("connEdit.shell.ksh");
            default:
                return preset;
        }
    }

    /**
     * The command to persist/launch for the selected {@code preset}. {@code customText} is used only
     * for {@link #CUSTOM}; the detected commands back the Windows sentinel presets. Returns {@code null}
     * for {@code CUSTOM} with no text (so the connector falls back to the OS default at launch).
     */
    static String commandFor(String preset, String customText,
                             String gitBashCommand, String cygwinCommand, String wslCommand) {
        if (CUSTOM.equals(preset)) {
            String custom = customText != null ? customText.trim() : "";
            return custom.isEmpty() ? null : custom;
        }
        if (GIT_BASH.equals(preset)) {
            return gitBashCommand;
        }
        if (CYGWIN.equals(preset)) {
            return cygwinCommand;
        }
        if (WSL.equals(preset)) {
            return wslCommand;
        }
        return preset; // powershell.exe, cmd.exe, zsh, bash, ksh — the id is the command.
    }

    /**
     * Maps a stored command back to a preset id for display. Falls back to {@link #CUSTOM} when the
     * command does not match a preset that is actually available on this OS (the caller then shows the
     * raw command in the custom field). A blank command selects the OS default preset.
     */
    static String presetForCommand(String command,
                                   String gitBashCommand, String cygwinCommand, String wslCommand) {
        List<String> available = presetsForCurrentOs(gitBashCommand, cygwinCommand, wslCommand);
        if (command == null || command.isBlank()) {
            return available.get(0);
        }
        String trimmed = command.trim();
        String match = null;
        if (POWERSHELL.equalsIgnoreCase(trimmed)) {
            match = POWERSHELL;
        } else if (CMD.equalsIgnoreCase(trimmed)) {
            match = CMD;
        } else if (ZSH.equalsIgnoreCase(trimmed)) {
            match = ZSH;
        } else if (BASH.equalsIgnoreCase(trimmed)) {
            match = BASH;
        } else if (KSH.equalsIgnoreCase(trimmed)) {
            match = KSH;
        } else if (gitBashCommand != null && isGitBashCommand(trimmed, gitBashCommand)) {
            match = GIT_BASH;
        } else if (cygwinCommand != null && isCygwinCommand(trimmed, cygwinCommand)) {
            match = CYGWIN;
        } else if (wslCommand != null && isWslCommand(trimmed)) {
            match = WSL;
        }
        return (match != null && available.contains(match)) ? match : CUSTOM;
    }

    /** True if the stored command is a Git Bash launch (matches the detected one or a git bash.exe path). */
    private static boolean isGitBashCommand(String command, String gitBashCommand) {
        String trimmed = command.trim();
        if (gitBashCommand != null && gitBashCommand.equalsIgnoreCase(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains("bash.exe") && (lower.contains("\\git\\") || lower.contains("/git/"));
    }

    /** True if the stored command is a Cygwin launch (matches the detected one or a cygwin bash.exe path). */
    private static boolean isCygwinCommand(String command, String cygwinCommand) {
        String trimmed = command.trim();
        if (cygwinCommand != null && cygwinCommand.equalsIgnoreCase(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains("cygwin") && lower.contains("bash.exe");
    }

    /** True if the stored command launches {@code wsl.exe}. */
    private static boolean isWslCommand(String command) {
        List<String> tokens = ServerConnection.tokenizeLocalShellCommand(command);
        if (tokens.isEmpty()) {
            return false;
        }
        String exe = tokens.get(0).replace('\\', '/').replaceAll(".*/", "").toLowerCase(Locale.ROOT);
        return exe.equals("wsl.exe") || exe.equals("wsl");
    }
}
