package de.kortty.ui;

import de.kortty.core.WorkflowScriptSupport;
import de.kortty.core.WorkflowScriptSupport.HeaderFacts;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.core.WorkflowScriptSupport.SwarmHost;
import de.kortty.core.WorkflowScriptSupport.SwarmScriptOption;
import de.kortty.core.WorkflowScriptSupport.WorkflowContext;
import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SwarmWorkflowScriptDialogTest {

    private static final String KEY_BODY =
        "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW";

    @Test
    void swarmPromptNeverCarriesTheTextOfATemporaryKey() {
        ServerConnection connection = new ServerConnection("web", "web.example.test", 22, "deploy");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath("TEMPORARY:-----BEGIN OPENSSH PRIVATE KEY-----\n"
            + KEY_BODY + "\n-----END OPENSSH PRIVATE KEY-----\n");

        List<SwarmHost> hosts = SwarmWorkflowScriptDialog.buildHosts(List.of(connection));
        String prompt = WorkflowScriptSupport.buildSwarmUserPrompt(
            ScriptLanguage.BASH,
            new HeaderFacts("deploy.sh", "tester", "deploy", null, LocalDateTime.of(2026, 10, 2, 12, 0),
                "Deploy", "Test model"),
            new WorkflowContext("- uptime", false, 1, 1),
            hosts, SwarmScriptOption.defaults(), null, WorkflowScriptSupport.HeaderMode.AUTO);

        assertThat(prompt).contains("deploy@web.example.test:22");
        assertThat(prompt).contains("key=" + SwarmHost.TEMPORARY_KEY_PLACEHOLDER);
        assertThat(prompt).doesNotContain("PRIVATE KEY");
        assertThat(prompt).doesNotContain(KEY_BODY);
        assertThat(prompt).doesNotContain("TEMPORARY:");
    }

    @Test
    void keyFilePathStillReachesTheSwarmHost() {
        ServerConnection connection = new ServerConnection("db", "db.example.test", 2222, "admin");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath("~/.ssh/id_ed25519");

        List<SwarmHost> hosts = SwarmWorkflowScriptDialog.buildHosts(List.of(connection));

        assertThat(hosts).hasSize(1);
        assertThat(hosts.get(0).keyPath()).isEqualTo("~/.ssh/id_ed25519");
    }
}
