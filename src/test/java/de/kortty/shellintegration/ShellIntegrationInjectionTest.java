package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.shellintegration.ShellIntegrationInjection.Plan;
import de.kortty.shellintegration.ShellIntegrationInjection.Support;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * What korTTY starts instead of a local bash, zsh or fish whose connection asked for shell
 * integration, and what the wrapper files say. Whether the shells accept the files, and run the
 * user's own startup files before the snippet, is pinned in {@code ShellIntegrationWrapperShellsTest}.
 */
class ShellIntegrationInjectionTest {

    private static final String DIR = "/home/ada/.kortty/shell-integration/tab-0123";

    private static final Map<String, String> ENV = Map.of("HOME", "/home/ada", "TERM", "xterm-256color");

    @Test
    void supportsTheThreeShellsWithStartupFlagsOnly() {
        assertThat(ShellIntegrationInjection.support(List.of("/bin/bash"), false)).isEqualTo(Support.SUPPORTED);
        assertThat(ShellIntegrationInjection.support(List.of("zsh", "-l"), false)).isEqualTo(Support.SUPPORTED);
        assertThat(ShellIntegrationInjection.support(List.of("/opt/homebrew/bin/fish", "--login", "-i"), false))
            .isEqualTo(Support.SUPPORTED);
        assertThat(ShellIntegrationInjection.support(List.of("bash", "-il"), false)).isEqualTo(Support.SUPPORTED);
        assertThat(ShellIntegrationInjection.support(List.of("fish", "--interactive"), false)).isEqualTo(Support.SUPPORTED);

        assertThat(ShellIntegrationInjection.support(List.of("bash", "-c", "make"), false))
            .isEqualTo(Support.OTHER_ARGUMENTS);
        assertThat(ShellIntegrationInjection.support(List.of("bash", "--norc"), false)).isEqualTo(Support.OTHER_ARGUMENTS);
        assertThat(ShellIntegrationInjection.support(List.of("bash", "--rcfile", "/etc/x"), false))
            .isEqualTo(Support.OTHER_ARGUMENTS);
        assertThat(ShellIntegrationInjection.support(List.of("zsh", "-f"), false)).isEqualTo(Support.OTHER_ARGUMENTS);
        assertThat(ShellIntegrationInjection.support(List.of("zsh", "script.zsh"), false))
            .isEqualTo(Support.OTHER_ARGUMENTS);
        assertThat(ShellIntegrationInjection.support(List.of("bash", "--interactive"), false))
            .isEqualTo(Support.OTHER_ARGUMENTS);

        assertThat(ShellIntegrationInjection.support(List.of("/bin/sh"), false)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("ksh", "-l"), false)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("ssh", "host"), false)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("tmux"), false)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of(), false)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(null, false)).isEqualTo(Support.OTHER_SHELL);
    }

    @Test
    void onWindowsOnlyAFullPathOutsideTheWindowsFolderCounts() {
        assertThat(ShellIntegrationInjection.support(
            List.of("C:\\Program Files\\Git\\bin\\bash.exe", "--login", "-i"), true)).isEqualTo(Support.SUPPORTED);
        assertThat(ShellIntegrationInjection.support(List.of("C:\\cygwin64\\bin\\bash.exe", "--login", "-i"), true))
            .isEqualTo(Support.SUPPORTED);
        assertThat(ShellIntegrationInjection.support(List.of("C:\\msys64\\usr\\bin\\zsh.exe"), true))
            .isEqualTo(Support.SUPPORTED);

        // WSL's bash.exe, also when PATH would find it, cannot read a Windows path.
        assertThat(ShellIntegrationInjection.support(List.of("C:\\Windows\\System32\\bash.exe"), true))
            .isEqualTo(Support.OTHER_SHELL);
        // The app-execution alias Windows installs for WSL lives in the user profile, but starts WSL too.
        assertThat(ShellIntegrationInjection.support(
            List.of("C:\\Users\\Ana\\AppData\\Local\\Microsoft\\WindowsApps\\bash.exe"), true))
            .isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("bash.exe"), true)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("bash"), true)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("wsl.exe"), true)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("powershell.exe"), true)).isEqualTo(Support.OTHER_SHELL);
        assertThat(ShellIntegrationInjection.support(List.of("cmd.exe"), true)).isEqualTo(Support.OTHER_SHELL);
    }

    @Test
    void bashStartsWithTheRcfileRightAfterTheProgram() {
        Plan plan = ShellIntegrationInjection.plan(List.of("/bin/bash"), ENV, DIR, false).orElseThrow();

        assertThat(plan.shell()).isEqualTo(ShellIntegrationSnippet.BASH);
        assertThat(plan.command()).containsExactly("/bin/bash", "--rcfile", DIR + "/bashrc").inOrder();
        assertThat(plan.environment()).isEqualTo(ENV);
        assertThat(plan.forwardedVariables()).isEmpty();
        assertThat(plan.files().keySet()).containsExactly("bashrc");

        String rc = plan.files().get("bashrc");
        assertThat(rc).contains("if [ -r ~/.bashrc ]; then . ~/.bashrc; fi\n");
        assertThat(rc).doesNotContain("/etc/profile");
        assertThat(rc.indexOf(". ~/.bashrc")).isLessThan(rc.indexOf(ShellIntegrationSnippet.BASH.text()));
        assertThat(rc).endsWith(ShellIntegrationSnippet.BASH.text());
    }

    @Test
    void aLoginBashDropsTheLoginFlagAndReadsTheLoginFilesInstead() {
        Plan gitBash = ShellIntegrationInjection.plan(
            List.of("C:\\Program Files\\Git\\bin\\bash.exe", "--login", "-i"), ENV, "C:/Users/ada/.kortty/x", true)
            .orElseThrow();
        assertThat(gitBash.command()).containsExactly(
            "C:\\Program Files\\Git\\bin\\bash.exe", "--rcfile", "C:/Users/ada/.kortty/x/bashrc", "-i").inOrder();

        assertThat(ShellIntegrationInjection.plan(List.of("bash", "-il"), ENV, DIR, false).orElseThrow().command())
            .containsExactly("bash", "--rcfile", DIR + "/bashrc", "-i").inOrder();
        Plan login = ShellIntegrationInjection.plan(List.of("bash", "-l"), ENV, DIR, false).orElseThrow();
        assertThat(login.command()).containsExactly("bash", "--rcfile", DIR + "/bashrc").inOrder();

        String rc = login.files().get("bashrc");
        assertThat(rc).contains("if [ -r /etc/profile ]; then . /etc/profile; fi\n"
            + "if [ -r ~/.bash_profile ]; then . ~/.bash_profile\n"
            + "elif [ -r ~/.bash_login ]; then . ~/.bash_login\n"
            + "elif [ -r ~/.profile ]; then . ~/.profile\n"
            + "fi\n");
        assertThat(rc).doesNotContain(". ~/.bashrc");
        assertThat(rc).endsWith(ShellIntegrationSnippet.BASH.text());
    }

    @Test
    void zshKeepsItsCommandAndGetsZdotdirAndTheUsersZdotdirHandedOver() {
        Map<String, String> env = Map.of("HOME", "/home/ada", "ZDOTDIR", "/home/ada/.config/zsh");
        Plan plan = ShellIntegrationInjection.plan(List.of("/bin/zsh", "-l"), env, DIR, false).orElseThrow();

        assertThat(plan.shell()).isEqualTo(ShellIntegrationSnippet.ZSH);
        assertThat(plan.command()).containsExactly("/bin/zsh", "-l").inOrder();
        assertThat(plan.environment()).containsEntry("ZDOTDIR", DIR + "/zsh");
        assertThat(plan.environment()).containsEntry(ShellIntegrationInjection.USER_ZDOTDIR_VARIABLE,
            "/home/ada/.config/zsh");
        assertThat(plan.forwardedVariables())
            .containsExactly(ShellIntegrationInjection.USER_ZDOTDIR_VARIABLE, "ZDOTDIR");
        assertThat(plan.files().keySet()).containsExactly("zsh/.zshenv", "zsh/.zprofile", "zsh/.zshrc").inOrder();
    }

    @Test
    void zshWithoutAZdotdirHandsNothingOverAndNeverInheritsTheHandOver() {
        Map<String, String> env = Map.of("HOME", "/home/ada",
            ShellIntegrationInjection.USER_ZDOTDIR_VARIABLE, "/stale/from/korTTYs/own/environment");
        Plan plan = ShellIntegrationInjection.plan(List.of("zsh"), env, DIR, false).orElseThrow();

        assertThat(plan.environment()).doesNotContainKey(ShellIntegrationInjection.USER_ZDOTDIR_VARIABLE);
        assertThat(plan.environment()).containsEntry("ZDOTDIR", DIR + "/zsh");
        assertThat(plan.forwardedVariables()).containsExactly("ZDOTDIR");
    }

    @Test
    void theZshWrappersRunTheUsersFilesAndPutZdotdirBackBeforeTheSnippet() {
        Map<String, String> files = ShellIntegrationInjection.plan(List.of("zsh"), ENV, DIR, false)
            .orElseThrow().files();
        String quoted = "'" + DIR + "/zsh'";

        String zshenv = files.get("zsh/.zshenv");
        assertThat(zshenv).contains("export ZDOTDIR=$KORTTY_SI_ZDOTDIR");
        assertThat(zshenv).contains("unset KORTTY_SI_ZDOTDIR");
        assertThat(zshenv).contains("builtin source -- \"${ZDOTDIR:-$HOME}/.zshenv\"");
        assertThat(zshenv).contains("if [[ -o interactive && -o rcs ]]; then");
        assertThat(zshenv).contains("export ZDOTDIR=" + quoted);
        assertThat(zshenv).doesNotContain("__kortty_si_loaded");

        String zprofile = files.get("zsh/.zprofile");
        assertThat(zprofile).contains("builtin source -- \"${ZDOTDIR:-$HOME}/.zprofile\"");

        String zshrc = files.get("zsh/.zshrc");
        int restore = zshrc.indexOf("export ZDOTDIR=$__kortty_si_zdotdir; else unset ZDOTDIR; fi");
        int user = zshrc.indexOf("builtin source -- \"${ZDOTDIR:-$HOME}/.zshrc\"");
        int snippet = zshrc.indexOf(ShellIntegrationSnippet.ZSH.text());
        assertThat(restore).isAtLeast(0);
        assertThat(user).isGreaterThan(restore);
        assertThat(snippet).isGreaterThan(user);
        assertThat(zshrc).endsWith(ShellIntegrationSnippet.ZSH.text());
        // After .zshrc, ZDOTDIR is the user's again, so it must not switch back to the wrapper.
        assertThat(zshrc).doesNotContain("export ZDOTDIR=" + quoted);
        // /etc/zshrc on macOS puts the history in ${ZDOTDIR:-$HOME}, which would be this folder.
        assertThat(zshrc).contains("if [[ ${HISTFILE-} == " + quoted + "/* ]]; then");
        // Never loops into its own folder.
        assertThat(zshrc).contains("${ZDOTDIR:-$HOME} != " + quoted);
    }

    @Test
    void fishSourcesTheSnippetAfterItsOwnConfiguration() {
        Plan plan = ShellIntegrationInjection.plan(List.of("/usr/bin/fish", "-l"), ENV, DIR, false).orElseThrow();

        assertThat(plan.shell()).isEqualTo(ShellIntegrationSnippet.FISH);
        assertThat(plan.command()).containsExactly(
            "/usr/bin/fish", "--init-command", "source '" + DIR + "/kortty.fish'", "-l").inOrder();
        assertThat(plan.files()).containsExactly("kortty.fish", ShellIntegrationSnippet.FISH.text());
        assertThat(plan.environment()).isEqualTo(ENV);
    }

    @Test
    void quotesAFolderWithQuotesAndBackslashesForEachShell() {
        String odd = "/home/o'brien/back\\slash dir";
        assertThat(ShellIntegrationInjection.posixQuote(odd)).isEqualTo("'/home/o'\\''brien/back\\slash dir'");
        assertThat(ShellIntegrationInjection.fishQuote(odd)).isEqualTo("'/home/o\\'brien/back\\\\slash dir'");

        Plan fish = ShellIntegrationInjection.plan(List.of("fish"), ENV, odd, false).orElseThrow();
        assertThat(fish.command().get(2)).isEqualTo("source '/home/o\\'brien/back\\\\slash dir/kortty.fish'");
        Plan zsh = ShellIntegrationInjection.plan(List.of("zsh"), ENV, odd, false).orElseThrow();
        assertThat(zsh.files().get("zsh/.zshenv")).contains("export ZDOTDIR='/home/o'\\''brien/back\\slash dir/zsh'");
    }

    @Test
    void refusesAFolderAShellCannotReadBack() {
        assertThat(ShellIntegrationInjection.plan(List.of("zsh"), ENV, "relative/dir", false)).isEmpty();
        assertThat(ShellIntegrationInjection.plan(List.of("zsh"), ENV, "/with\nnewline", false)).isEmpty();
        assertThat(ShellIntegrationInjection.plan(List.of("zsh"), ENV, "", false)).isEmpty();
        assertThat(ShellIntegrationInjection.plan(List.of("zsh"), ENV, "/trailing/", false)).isEmpty();
        assertThat(ShellIntegrationInjection.planForHostLoginShell(ENV, "/with\ttab")).isEmpty();
        assertThat(ShellIntegrationInjection.plan(List.of("ksh"), ENV, DIR, false)).isEmpty();
        assertThat(ShellIntegrationInjection.plan(List.of("bash", "-c", "x"), ENV, DIR, false)).isEmpty();
    }

    @Test
    void theFlatpakHostLoginShellPicksTheWrapperForTheHostsShell() {
        Plan plan = ShellIntegrationInjection.planForHostLoginShell(
            Map.of("HOME", "/home/ada", ShellIntegrationInjection.USER_ZDOTDIR_VARIABLE, "/stale"), DIR).orElseThrow();

        assertThat(plan.shell()).isNull();
        assertThat(plan.command()).hasSize(3);
        assertThat(plan.command().subList(0, 2)).containsExactly("/bin/sh", "-lc").inOrder();
        String script = plan.command().get(2);
        assertThat(script).startsWith("case ${SHELL##*/} in\n");
        assertThat(script).contains("bash) exec \"$SHELL\" --rcfile '" + DIR + "/bashrc' ;;");
        assertThat(script).contains("ZDOTDIR='" + DIR + "/zsh'; export ZDOTDIR; exec \"$SHELL\" -l ;;");
        assertThat(script).contains("KORTTY_SI_ZDOTDIR=$ZDOTDIR; export KORTTY_SI_ZDOTDIR;");
        assertThat(script).contains("fish) exec \"$SHELL\" -l --init-command 'source '\\''" + DIR + "/kortty.fish'\\''' ;;");
        // Any other shell starts exactly as korTTY's default host command starts it.
        assertThat(script).contains("*) exec \"${SHELL:-/bin/sh}\" -l ;;");
        assertThat(plan.files().keySet())
            .containsExactly("bashrc", "zsh/.zshenv", "zsh/.zprofile", "zsh/.zshrc", "kortty.fish").inOrder();
        // The host's bash came with -l, so its wrapper reads the login files.
        assertThat(plan.files().get("bashrc")).contains(". /etc/profile");
        assertThat(plan.environment()).doesNotContainKey(ShellIntegrationInjection.USER_ZDOTDIR_VARIABLE);
        assertThat(plan.forwardedVariables()).isEmpty();
    }

    @Test
    void everyWrapperSaysItIsKorttysAndThatYourFilesAreNotChanged() {
        Map<String, String> files = ShellIntegrationInjection.planForHostLoginShell(ENV, DIR).orElseThrow().files();
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (file.getKey().equals("kortty.fish")) {
                continue;
            }
            assertThat(file.getValue()).startsWith("# korTTY shell integration for this tab only");
            assertThat(file.getValue()).doesNotContain("\r");
        }
        assertThat(files.get("bashrc")).contains("Your files are\n# not changed");
        assertThat(files.get("zsh/.zshenv")).contains("Your files are not changed");
    }
}
