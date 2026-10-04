package de.kortty.shellintegration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adds korTTY's shell integration to a <b>local</b> bash, zsh or fish without changing a single
 * startup file of the user: the shell is started with a startup file of korTTY's own, a wrapper,
 * that runs the user's files first and then the snippet {@link ShellIntegrationSnippet} ships,
 * exactly where the guide tells users to put it.
 *
 * <ul>
 *   <li><b>bash</b>: {@code --rcfile <dir>/bashrc}. The wrapper runs {@code ~/.bashrc} and then the
 *       snippet. A login bash ({@code -l}, {@code --login}, as Git Bash starts) would ignore
 *       {@code --rcfile}, so it is started without the login flag and its wrapper reads what a login
 *       bash reads instead: {@code /etc/profile} and the first of {@code ~/.bash_profile},
 *       {@code ~/.bash_login} and {@code ~/.profile}.</li>
 *   <li><b>zsh</b>: {@code ZDOTDIR=<dir>/zsh}. Its {@code .zshenv}, {@code .zprofile} and {@code .zshrc}
 *       each run the user's file of the same name from the ZDOTDIR zsh would have used (handed over
 *       in {@value #USER_ZDOTDIR_VARIABLE}) or the home directory, follow a ZDOTDIR the user's
 *       {@code .zshenv} sets, and point ZDOTDIR back before the snippet runs at the end of
 *       {@code .zshrc}; zsh then reads the user's {@code .zlogin} itself. A non-interactive zsh gets
 *       ZDOTDIR back in {@code .zshenv} already.</li>
 *   <li><b>fish</b>: {@code --init-command "source <dir>/kortty.fish"}, which fish runs after its own
 *       configuration.</li>
 * </ul>
 *
 * <p>Only a shell started with no other arguments than {@code -i}, {@code -l} and {@code --login} is
 * changed: anything else ({@code -c}, {@code --norc}, a script) means the user controls the startup
 * and it stays as configured. On Windows the shell must be named with its full path, outside the
 * Windows system folder, so the {@code bash.exe} that starts WSL is never handed a Windows path.</p>
 *
 * <p>Only for local shell connections: korTTY never injects anything into an SSH, Mosh or Teamwork
 * connection (an SSH bootstrap once caused a duplicate login prompt, commit 05f5a138). The caller
 * decides that; this class only computes the command line, the environment and the files, which
 * {@link ShellIntegrationWrapperDirectory} writes. FX-free and stateless.</p>
 */
public final class ShellIntegrationInjection {

    /** The variable through which the zsh wrapper learns the ZDOTDIR zsh would have used. */
    public static final String USER_ZDOTDIR_VARIABLE = "KORTTY_SI_ZDOTDIR";

    /** The wrapper bash reads instead of {@code ~/.bashrc}. */
    static final String BASH_RCFILE = "bashrc";

    /** The folder ZDOTDIR points at for zsh. */
    static final String ZSH_DIRECTORY = "zsh";

    /** The snippet fish sources after its configuration. */
    static final String FISH_FILE = "kortty.fish";

    private static final String ZDOTDIR = "ZDOTDIR";

    /** Whether a command line can be given shell integration. */
    public enum Support {
        /** bash, zsh or fish with no other arguments than {@code -i}, {@code -l} and {@code --login}. */
        SUPPORTED,
        /** Not bash, zsh or fish, or on Windows not named with a full path outside the system folder. */
        OTHER_SHELL,
        /** One of the three, but started with other arguments, which leave the startup to the user. */
        OTHER_ARGUMENTS
    }

    /**
     * What to start instead of the configured command.
     *
     * @param shell the shell the wrapper is for, or null when the host's {@code $SHELL} decides
     *     ({@link #planForHostLoginShell})
     * @param command the command line to start
     * @param environment the complete environment to start it with
     * @param forwardedVariables the variables of {@code environment} the shell needs, which a
     *     Flatpak sandbox has to pass to the host explicitly
     * @param files the wrapper files by their path relative to the wrapper directory, with
     *     {@code /} as separator
     */
    public record Plan(
            @Nullable ShellIntegrationSnippet shell,
            @NotNull List<String> command,
            @NotNull Map<String, String> environment,
            @NotNull List<String> forwardedVariables,
            @NotNull Map<String, String> files) {

        public Plan {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            forwardedVariables = List.copyOf(forwardedVariables);
            files = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }

    private ShellIntegrationInjection() {
    }

    /**
     * Whether {@code command} (program and arguments, as the local shell starts it) can be given
     * shell integration.
     *
     * @param windows whether korTTY runs on Windows, where only a full path outside the Windows
     *     folder counts
     */
    public static @NotNull Support support(@Nullable List<String> command, boolean windows) {
        if (command == null || command.isEmpty() || command.get(0) == null) {
            return Support.OTHER_SHELL;
        }
        String program = command.get(0).strip();
        ShellIntegrationSnippet shell = ShellIntegrationSnippet.forShell(program);
        if (shell == null || program.startsWith("-") || (windows && !isWindowsShellPath(program))) {
            return Support.OTHER_SHELL;
        }
        for (String argument : command.subList(1, command.size())) {
            if (!isStartupFlag(shell, argument)) {
                return Support.OTHER_ARGUMENTS;
            }
        }
        return Support.SUPPORTED;
    }

    /**
     * The plan for {@code command}, or empty when it is not {@link Support#SUPPORTED} or the
     * directory cannot be written into a startup file safely.
     *
     * @param environment the environment the shell would start with
     * @param directory the wrapper directory as the shell reads paths, with {@code /} as separator
     * @param windows whether korTTY runs on Windows
     */
    public static @NotNull Optional<Plan> plan(@Nullable List<String> command,
            @NotNull Map<String, String> environment, @NotNull String directory, boolean windows) {
        if (support(command, windows) != Support.SUPPORTED || !isUsableDirectory(directory)) {
            return Optional.empty();
        }
        String program = command.get(0).strip();
        ShellIntegrationSnippet shell = ShellIntegrationSnippet.forShell(program);
        List<String> arguments = command.subList(1, command.size());
        Map<String, String> env = withoutHandOver(environment);
        return Optional.of(switch (shell) {
            case BASH -> bashPlan(program, arguments, env, directory);
            case ZSH -> zshPlan(command, environment, env, directory);
            case FISH -> fishPlan(program, arguments, env, directory);
        });
    }

    /**
     * The plan for the login shell of the Flatpak host, which korTTY starts through
     * {@code /bin/sh -lc 'exec "${SHELL:-/bin/sh}" -l'} because the sandbox cannot see which shell
     * that is: the same {@code /bin/sh} picks the wrapper for the host's {@code $SHELL}, and starts any
     * other shell as before. Empty when the directory cannot be written into a startup file safely.
     */
    public static @NotNull Optional<Plan> planForHostLoginShell(@NotNull Map<String, String> environment,
            @NotNull String directory) {
        if (!isUsableDirectory(directory)) {
            return Optional.empty();
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put(BASH_RCFILE, bashWrapper(true));
        files.putAll(zshWrappers(directory + "/" + ZSH_DIRECTORY));
        files.put(FISH_FILE, ShellIntegrationSnippet.FISH.text());
        String script = "case ${SHELL##*/} in\n"
            + "bash) exec \"$SHELL\" --rcfile " + posixQuote(directory + "/" + BASH_RCFILE) + " ;;\n"
            + "zsh) if [ -n \"${ZDOTDIR-}\" ]; then " + USER_ZDOTDIR_VARIABLE + "=$ZDOTDIR; export "
            + USER_ZDOTDIR_VARIABLE + "; fi; ZDOTDIR=" + posixQuote(directory + "/" + ZSH_DIRECTORY)
            + "; export ZDOTDIR; exec \"$SHELL\" -l ;;\n"
            + "fish) exec \"$SHELL\" -l --init-command " + posixQuote(fishSourceCommand(directory)) + " ;;\n"
            + "*) exec \"${SHELL:-/bin/sh}\" -l ;;\n"
            + "esac";
        return Optional.of(new Plan(null, List.of("/bin/sh", "-lc", script), withoutHandOver(environment),
            List.of(), files));
    }

    private static Plan bashPlan(String program, List<String> arguments, Map<String, String> env, String directory) {
        boolean login = false;
        List<String> command = new ArrayList<>();
        command.add(program);
        // Long options go before the single-letter ones, or bash rejects them.
        command.add("--rcfile");
        command.add(directory + "/" + BASH_RCFILE);
        for (String argument : arguments) {
            if (argument.equals("--login")) {
                login = true;
                continue;
            }
            if (argument.indexOf('l') >= 0) {
                login = true;
                argument = argument.replace("l", "");
            }
            if (!argument.equals("-")) {
                command.add(argument);
            }
        }
        return new Plan(ShellIntegrationSnippet.BASH, command, env, List.of(),
            Map.of(BASH_RCFILE, bashWrapper(login)));
    }

    private static Plan zshPlan(List<String> command, Map<String, String> environment, Map<String, String> env,
            String directory) {
        String zshDirectory = directory + "/" + ZSH_DIRECTORY;
        List<String> forwarded = new ArrayList<>();
        String userZdotdir = environment.get(ZDOTDIR);
        if (userZdotdir != null && !userZdotdir.isBlank()) {
            env.put(USER_ZDOTDIR_VARIABLE, userZdotdir);
            forwarded.add(USER_ZDOTDIR_VARIABLE);
        }
        env.put(ZDOTDIR, zshDirectory);
        forwarded.add(ZDOTDIR);
        List<String> unchanged = new ArrayList<>(command);
        unchanged.set(0, command.get(0).strip());
        return new Plan(ShellIntegrationSnippet.ZSH, unchanged, env, forwarded, zshWrappers(zshDirectory));
    }

    private static Plan fishPlan(String program, List<String> arguments, Map<String, String> env, String directory) {
        List<String> command = new ArrayList<>();
        command.add(program);
        command.add("--init-command");
        command.add(fishSourceCommand(directory));
        command.addAll(arguments);
        return new Plan(ShellIntegrationSnippet.FISH, command, env, List.of(),
            Map.of(FISH_FILE, ShellIntegrationSnippet.FISH.text()));
    }

    /** The bash wrapper: the user's startup files as bash would read them, then the snippet. */
    static String bashWrapper(boolean login) {
        StringBuilder text = new StringBuilder()
            .append("# korTTY shell integration for this tab only: korTTY started bash with --rcfile pointing\n")
            .append("# here. This file runs your own startup files and then korTTY's snippet. Your files are\n")
            .append("# not changed, and korTTY deletes this file when the tab closes.\n");
        if (login) {
            text.append("# A login bash ignores --rcfile, so this one runs what a login bash reads.\n")
                .append("if [ -r /etc/profile ]; then . /etc/profile; fi\n")
                .append("if [ -r ~/.bash_profile ]; then . ~/.bash_profile\n")
                .append("elif [ -r ~/.bash_login ]; then . ~/.bash_login\n")
                .append("elif [ -r ~/.profile ]; then . ~/.profile\n")
                .append("fi\n");
        } else {
            text.append("if [ -r ~/.bashrc ]; then . ~/.bashrc; fi\n");
        }
        return text.append('\n').append(ShellIntegrationSnippet.BASH.text()).toString();
    }

    /** The zsh wrappers by their path relative to the wrapper directory. */
    static Map<String, String> zshWrappers(String zshDirectory) {
        String wrapper = posixQuote(zshDirectory);
        String restore = "if (( ${__kortty_si_zdotdir_set:-0} )); then export ZDOTDIR=$__kortty_si_zdotdir;"
            + " else unset ZDOTDIR; fi\n";
        String remember = "    typeset -g __kortty_si_zdotdir_set=${+ZDOTDIR} __kortty_si_zdotdir=${ZDOTDIR-}\n"
            + "    export ZDOTDIR=" + wrapper + "\n";
        Map<String, String> files = new LinkedHashMap<>();
        files.put(ZSH_DIRECTORY + "/.zshenv",
            "# korTTY shell integration for this tab only: korTTY pointed ZDOTDIR here, so zsh reads its\n"
            + "# startup files from this folder. Each one runs yours from your own ZDOTDIR or home folder,\n"
            + "# .zshrc then adds korTTY's snippet and points ZDOTDIR back. Your files are not changed, and\n"
            + "# korTTY deletes this folder when the tab closes.\n"
            + "if [[ -n ${" + USER_ZDOTDIR_VARIABLE + "-} ]]; then\n"
            + "    export ZDOTDIR=$" + USER_ZDOTDIR_VARIABLE + "\n"
            + "else\n"
            + "    unset ZDOTDIR\n"
            + "fi\n"
            + "unset " + USER_ZDOTDIR_VARIABLE + "\n"
            + sourceUserFile(wrapper, ".zshenv")
            + "# Only an interactive zsh goes on to .zshrc; any other keeps your ZDOTDIR from here on.\n"
            + "if [[ -o interactive && -o rcs ]]; then\n"
            + remember
            + "fi\n");
        files.put(ZSH_DIRECTORY + "/.zprofile",
            "# korTTY shell integration for this tab only: runs your .zprofile; see .zshenv here.\n"
            + restore
            + sourceUserFile(wrapper, ".zprofile")
            + "if [[ -o rcs ]]; then\n"
            + remember
            + "else\n"
            + "    unset __kortty_si_zdotdir_set __kortty_si_zdotdir\n"
            + "fi\n");
        files.put(ZSH_DIRECTORY + "/.zshrc",
            "# korTTY shell integration for this tab only: runs your .zshrc and then korTTY's snippet;\n"
            + "# see .zshenv here. zsh reads your .zlogin itself.\n"
            + restore
            + "unset __kortty_si_zdotdir_set __kortty_si_zdotdir\n"
            + "# A global startup file may have put the history in this folder, which korTTY deletes.\n"
            + "if [[ ${HISTFILE-} == " + wrapper + "/* ]]; then\n"
            + "    HISTFILE=${ZDOTDIR:-$HOME}/${HISTFILE:t}\n"
            + "fi\n"
            + sourceUserFile(wrapper, ".zshrc")
            + "\n"
            + ShellIntegrationSnippet.ZSH.text());
        return files;
    }

    private static String sourceUserFile(String wrapper, String name) {
        return "if [[ ${ZDOTDIR:-$HOME} != " + wrapper + " && -r ${ZDOTDIR:-$HOME}/" + name + " ]]; then\n"
            + "    builtin source -- \"${ZDOTDIR:-$HOME}/" + name + "\"\n"
            + "fi\n";
    }

    private static String fishSourceCommand(String directory) {
        return "source " + fishQuote(directory + "/" + FISH_FILE);
    }

    /** The variable that hands the user's ZDOTDIR over is never inherited from korTTY's own environment. */
    private static Map<String, String> withoutHandOver(Map<String, String> environment) {
        Map<String, String> env = new HashMap<>(environment);
        env.remove(USER_ZDOTDIR_VARIABLE);
        return env;
    }

    private static boolean isStartupFlag(ShellIntegrationSnippet shell, String argument) {
        if (argument == null) {
            return false;
        }
        if (argument.equals("--login") || (shell == ShellIntegrationSnippet.FISH && argument.equals("--interactive"))) {
            return true;
        }
        return argument.matches("-[il]+");
    }

    /**
     * A full path, not in the Windows folder: {@code C:\Windows\System32\bash.exe} starts WSL, which
     * cannot read a startup file named by a Windows path, and a bare {@code bash} could be that one. So
     * does the app-execution alias in {@code %LOCALAPPDATA%\Microsoft\WindowsApps}; handed a Windows
     * path, WSL's bash would start without the user's own {@code ~/.bashrc}.
     */
    private static boolean isWindowsShellPath(String program) {
        String path = program.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.indexOf('/') < 0) {
            return false;
        }
        return !path.matches("^([a-z]:)?/windows/.*") && !path.contains("/system32/")
            && !path.contains("/syswow64/") && !path.contains("/sysnative/") && !path.contains("/windowsapps/");
    }

    /** A directory that every shell reads back as written: absolute, no control characters. */
    private static boolean isUsableDirectory(String directory) {
        if (directory == null || directory.isBlank() || directory.endsWith("/")) {
            return false;
        }
        for (int i = 0; i < directory.length(); i++) {
            char c = directory.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return directory.startsWith("/") || directory.matches("^[A-Za-z]:/.*");
    }

    /** Single quotes for sh, bash and zsh: a quote inside becomes {@code '\''}. */
    static String posixQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Single quotes for fish, where a backslash escapes a quote and itself. */
    static String fishQuote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
