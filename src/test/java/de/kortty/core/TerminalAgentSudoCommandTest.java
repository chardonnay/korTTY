package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class TerminalAgentSudoCommandTest {

    @Test
    void normalizesPlainSudoToNonInteractiveSudo() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo dnf install -y tomcat")).isEqualTo("sudo -n dnf install -y tomcat");
    }

    @Test
    void keepsAlreadyNonInteractiveSudoUnchanged() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo -n dnf install -y tomcat")).isEqualTo("sudo -n dnf install -y tomcat");
    }

    @Test
    void normalizesSudoAfterShellConnector() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("rpm -q tomcat || sudo dnf install -y tomcat")).isEqualTo("rpm -q tomcat || sudo -n dnf install -y tomcat");
    }

    @Test
    void normalizesSudoInAnIfAndAfterNewlinesWhilePreservingHereDocumentText() {
        String command = """
            if sudo true; then
                cat <<'EOF'
            sudo -i
            EOF
                sudo id
            fi
            """;

        assertThat(TerminalAgentService.normalizeSudoForAgentExecution(command)).isEqualTo("""
            if sudo -n true; then
                cat <<'EOF'
            sudo -i
            EOF
                sudo -n id
            fi
            """);
    }

    @Test
    void normalizesPlannerSudoStdinModeToRuntimeControlledNonInteractiveSudo() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo -S dnf install -y tomcat")).isEqualTo("sudo -n dnf install -y tomcat");
    }

    @Test
    void normalizesPlannerLongSudoStdinModeToRuntimeControlledNonInteractiveSudo() {
        assertThat(TerminalAgentService.normalizeSudoForAgentExecution("sudo --stdin find /etc -type f | wc -l")).isEqualTo("sudo -n find /etc -type f | wc -l");
    }

    @Test
    void rejectsSudoShellModeFromPlanner() {
        expectThrows(IllegalArgumentException.class, () ->
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -i"));
    }

    @Test
    void rejectsLowercaseSudoShellModeFromPlanner() {
        expectThrows(IllegalArgumentException.class, () ->
            TerminalAgentService.normalizeSudoForAgentExecution("sudo -s"));
    }

    @Test
    void rejectsSudoShellModeAfterAnotherSudoCommand() {
        expectThrows(IllegalArgumentException.class, () ->
            TerminalAgentService.normalizeSudoForAgentExecution("sudo true && sudo -i"));
    }

    @Test
    void ignoresInteractiveTokensInsideHereDocumentBody() {
        String command = """
            cat << 'EOF' > top_files.py
            import os
            top = get_top_files()
            for path, size in top:
                print(path, size)
            EOF
            """;

        assertThat(TerminalAgentService.isInteractiveCommand(command)).isFalse();
    }

    @Test
    void stillRejectsInteractiveCommandBeforeHereDocumentBody() {
        String command = """
            top << 'EOF'
            ignored
            EOF
            """;

        assertThat(TerminalAgentService.isInteractiveCommand(command)).isTrue();
    }

    @Test
    void doesNotFlagInteractiveTokensInsideQuotedScripts() {
        // Regression: "top" inside the single-quoted sed script must not be treated as the `top` command.
        String command = "sed -i '/- name: Show top 10 biggest files/,/debug:/c\\    debug:' "
            + "\"${PWD}/find_biggest_files.yml\"";
        assertThat(TerminalAgentService.isInteractiveCommand(command)).isFalse();

        assertThat(TerminalAgentService.isInteractiveCommand(
            "awk '{print $1}' more.log | sort")).isFalse();
        assertThat(TerminalAgentService.isInteractiveCommand(
            "echo 'less is more' > notes.txt")).isFalse();
    }

    @Test
    void doesNotFlagInteractiveTokensUsedAsArguments() {
        assertThat(TerminalAgentService.isInteractiveCommand("grep top /var/log/messages")).isFalse();
        assertThat(TerminalAgentService.isInteractiveCommand("ls -la less more top")).isFalse();
        assertThat(TerminalAgentService.isInteractiveCommand("find . -name 'man*' -type f")).isFalse();
    }

    @Test
    void stillFlagsRealInteractiveCommandsIncludingWrappedAndPiped() {
        assertThat(TerminalAgentService.isInteractiveCommand("vi /etc/hosts")).isTrue();
        assertThat(TerminalAgentService.isInteractiveCommand("cat /var/log/messages | less")).isTrue();
        assertThat(TerminalAgentService.isInteractiveCommand("sudo vim /etc/fstab")).isTrue();
        assertThat(TerminalAgentService.isInteractiveCommand("sudo -n nano /etc/hosts")).isTrue();
        assertThat(TerminalAgentService.isInteractiveCommand("/usr/bin/top")).isTrue();
    }

    @Test
    void requiresConfirmationForFileCreatingHereDocument() {
        String command = """
            cat > /home/daniel/find_xml.pl << 'EOF'
            #!/usr/bin/perl
            print if /\\.xml$/i;
            EOF
            """;

        assertThat(TerminalAgentService.requiresConfirmationByCommandShape(command)).isTrue();
    }

    @Test
    void requiresConfirmationForChmodCommand() {
        assertThat(TerminalAgentService.requiresConfirmationByCommandShape("chmod +x /home/daniel/find_xml.pl")).isTrue();
    }

    @Test
    void doesNotRequireConfirmationForReadOnlyFindCommand() {
        assertThat(TerminalAgentService.requiresConfirmationByCommandShape("find . -name '*.xml' -type f -printf '%s %p\\n' | sort -nr | head")).isFalse();
    }

    @Test
    void requestsApprovalWhenAnyCommandRequiresConfirmation() {
        List<TerminalAgentModels.PlannedCommand> commands = List.of(
            new TerminalAgentModels.PlannedCommand(
                "cat > /home/daniel/find_xml.pl",
                "Create script",
                TerminalAgentModels.Risk.REQUIRES_CONFIRMATION));

        assertThat(TerminalAgentService.shouldRequestApproval(
            TerminalAgentService.AgentDecisionStatus.run_commands,
            commands,
            false)).isTrue();
    }
}
