package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class GitHubSkillReferenceTest {

    @Test
    void repositoryAloneListsEverySkill() throws Exception {
        GitHubSkillReference reference = GitHubSkillReference.parse("anthropics/skills");

        assertThat(reference).isEqualTo(new GitHubSkillReference("anthropics", "skills", null, null, null));
    }

    @Test
    void atSuffixNamesOneSkillAsSkillDirectoriesPrintIt() throws Exception {
        assertThat(GitHubSkillReference.parse("vercel-labs/agent-skills@react-best-practices"))
            .isEqualTo(new GitHubSkillReference("vercel-labs", "agent-skills", null, null, "react-best-practices"));
    }

    @Test
    void copiedNpxCommandsAreUnderstood() throws Exception {
        assertThat(GitHubSkillReference.parse("npx skills add anthropics/skills@pdf"))
            .isEqualTo(new GitHubSkillReference("anthropics", "skills", null, null, "pdf"));
        assertThat(GitHubSkillReference.parse("npx -y skills add anthropics/skills --skill docx"))
            .isEqualTo(new GitHubSkillReference("anthropics", "skills", null, null, "docx"));
    }

    @Test
    void shortPathPointsAtOneDirectoryAndDropsTheSkillFile() throws Exception {
        assertThat(GitHubSkillReference.parse("anthropics/skills/skills/pdf/SKILL.md"))
            .isEqualTo(new GitHubSkillReference("anthropics", "skills", null, "skills/pdf", null));
    }

    @Test
    void treeAndBlobUrlsCarryRefAndPath() throws Exception {
        assertThat(GitHubSkillReference.parse("https://github.com/affaan-m/ECC/tree/main/skills/kubernetes-patterns"))
            .isEqualTo(new GitHubSkillReference("affaan-m", "ECC", "main", "skills/kubernetes-patterns", null));
        assertThat(GitHubSkillReference.parse("https://github.com/o/r/blob/v1.2/SKILL.md"))
            .isEqualTo(new GitHubSkillReference("o", "r", "v1.2", "", null));
        assertThat(GitHubSkillReference.parse("https://github.com/o/r.git"))
            .isEqualTo(new GitHubSkillReference("o", "r", null, null, null));
    }

    @Test
    void anEncodedSlashKeepsABranchNameTogether() throws Exception {
        assertThat(GitHubSkillReference.parse("https://github.com/o/r/tree/feature%2Fskills/a%20b"))
            .isEqualTo(new GitHubSkillReference("o", "r", "feature/skills", "a b", null));
    }

    @Test
    void refusesWhatCouldEscapeTheRepository() {
        for (String bad : new String[] {"", "justone", "o/r/../x", "o/r@a/b", "https://github.com/o",
                "https://github.com/o/r/pulls/1", "o/r/x@y", "bad owner/r"}) {
            ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class, () -> GitHubSkillReference.parse(bad));
            assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.INVALID_REFERENCE);
        }
    }

    @Test
    void displayNameIsTheSkillDirectory() throws Exception {
        assertThat(GitHubSkillReference.parse("o/r/skills/pdf").displayName()).isEqualTo("pdf");
        assertThat(GitHubSkillReference.parse("o/r@docx").displayName()).isEqualTo("docx");
        assertThat(GitHubSkillReference.parse("o/r").displayName()).isEqualTo("r");
    }
}
