package de.kortty.core;

import de.kortty.model.AgentActionCategory;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AgentActionCategoryTest {

    @Test
    void classifiesCommonCommandsByLeadingTool() {
        assertThat(AgentActionCategory.classify("rm -rf /tmp/x")).isEqualTo(AgentActionCategory.WRITE);
        assertThat(AgentActionCategory.classify("cp a b")).isEqualTo(AgentActionCategory.WRITE);
        assertThat(AgentActionCategory.classify("cat /var/log/messages")).isEqualTo(AgentActionCategory.READ);
        assertThat(AgentActionCategory.classify("grep -i error file.log")).isEqualTo(AgentActionCategory.READ);
        assertThat(AgentActionCategory.classify("mkdir -p /opt/app")).isEqualTo(AgentActionCategory.DIRECTORY);
        assertThat(AgentActionCategory.classify("apt-get install -y nginx")).isEqualTo(AgentActionCategory.PACKAGE);
        assertThat(AgentActionCategory.classify("systemctl restart sshd")).isEqualTo(AgentActionCategory.SERVICE);
        assertThat(AgentActionCategory.classify("curl -s https://example.com")).isEqualTo(AgentActionCategory.NETWORK);
        assertThat(AgentActionCategory.classify("ps aux")).isEqualTo(AgentActionCategory.INSPECT);
        assertThat(AgentActionCategory.classify("ansible-playbook site.yml")).isEqualTo(AgentActionCategory.EXECUTE);
        assertThat(AgentActionCategory.classify("./deploy.sh")).isEqualTo(AgentActionCategory.EXECUTE);
    }

    @Test
    void treatsWriteRedirectionAndHeredocAsWrite() {
        // A here-document / redirection that creates a file is a WRITE, even though it starts with `cat`.
        assertThat(AgentActionCategory.classify("cat > /home/d/play.yml <<'EOF'\n---\nEOF"))
            .isEqualTo(AgentActionCategory.WRITE);
        assertThat(AgentActionCategory.classify("echo hi >> notes.txt")).isEqualTo(AgentActionCategory.WRITE);
        // In-place sed edits mutate files.
        assertThat(AgentActionCategory.classify("sed -i 's/a/b/' file")).isEqualTo(AgentActionCategory.WRITE);
        // ...but a plain sed transform / read stays non-write.
        assertThat(AgentActionCategory.classify("sed 's/a/b/' file")).isNotEqualTo(AgentActionCategory.WRITE);
        // A quoted ">" inside a string is not a redirection.
        assertThat(AgentActionCategory.classify("grep '>' file.txt")).isEqualTo(AgentActionCategory.READ);
    }

    @Test
    void skipsWrappersAndEnvAssignmentsAndPaths() {
        assertThat(AgentActionCategory.classify("sudo systemctl status nginx")).isEqualTo(AgentActionCategory.SERVICE);
        assertThat(AgentActionCategory.classify("sudo -n rm /tmp/x")).isEqualTo(AgentActionCategory.WRITE);
        assertThat(AgentActionCategory.classify("FOO=bar env BAZ=1 cat file")).isEqualTo(AgentActionCategory.READ);
        assertThat(AgentActionCategory.classify("/usr/bin/grep x f")).isEqualTo(AgentActionCategory.READ);
    }

    @Test
    void defaultsToGenericForBlankOrUnknown() {
        assertThat(AgentActionCategory.classify(null)).isEqualTo(AgentActionCategory.GENERIC);
        assertThat(AgentActionCategory.classify("   ")).isEqualTo(AgentActionCategory.GENERIC);
        assertThat(AgentActionCategory.classify("frobnicate --widget")).isEqualTo(AgentActionCategory.GENERIC);
    }

    @Test
    void everyCategoryHasANonBlankEmoji() {
        for (AgentActionCategory category : AgentActionCategory.values()) {
            assertThat(category.emoji()).isNotEmpty();
        }
    }
}
