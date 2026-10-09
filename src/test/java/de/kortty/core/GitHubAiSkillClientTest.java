package de.kortty.core;

import de.kortty.model.AiSkillProviderAuth;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class GitHubAiSkillClientTest {

    private static final String PDF_SKILL = "---\nname: pdf\ndescription: Work with PDF files.\n---\n\n# PDF\nUse pdftotext.\n";

    private static StubSkillServer.Reply contents(String path, String sha, String markdown) {
        String base64 = Base64.getMimeEncoder().encodeToString(markdown.getBytes(StandardCharsets.UTF_8));
        return StubSkillServer.Reply.json("{\"type\":\"file\",\"path\":\"" + path + "\",\"sha\":\"" + sha + "\",\"size\":"
            + markdown.length() + ",\"encoding\":\"base64\",\"html_url\":\"https://github.com/anthropics/skills/blob/main/"
            + path + "\",\"content\":\"" + base64.replace("\r\n", "\\n") + "\"}");
    }

    private static StubSkillServer stubRepository() throws Exception {
        return new StubSkillServer()
            .reply("/repos/anthropics/skills", StubSkillServer.Reply.json("{\"default_branch\":\"main\"}"))
            .reply("/repos/anthropics/skills/git/trees/main?recursive=1", StubSkillServer.Reply.json(
                "{\"truncated\":false,\"tree\":["
                    + "{\"path\":\"README.md\",\"type\":\"blob\"},"
                    + "{\"path\":\"skills\",\"type\":\"tree\"},"
                    + "{\"path\":\"skills/pdf/SKILL.md\",\"type\":\"blob\"},"
                    + "{\"path\":\"skills/pdf/scripts/extract.py\",\"type\":\"blob\"},"
                    + "{\"path\":\"skills/docx/SKILL.md\",\"type\":\"blob\"},"
                    + "{\"path\":\"notes/skill.md\",\"type\":\"blob\"}]}"))
            .reply("/repos/anthropics/skills/contents/skills/pdf/SKILL.md?ref=main",
                contents("skills/pdf/SKILL.md", "blobsha1234567", PDF_SKILL));
    }

    @Test
    void listsEverySkillDirectoryOfARepositoryOnItsDefaultBranch() throws Exception {
        try (StubSkillServer stub = stubRepository()) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);

            List<ExternalAiSkillCandidate> candidates = client.search("anthropics/skills");

            assertThat(candidates.stream().map(ExternalAiSkillCandidate::name).toList())
                .containsExactly("pdf", "docx").inOrder();
            assertThat(candidates.get(0).reference()).isEqualTo(stub.baseUrl() + "/anthropics/skills/tree/main/skills/pdf");
            assertThat(candidates.get(0).author()).isEqualTo("anthropics/skills");
        }
    }

    @Test
    void fetchesOneSkillByItsDirectoryNameAndStoresTheBlobShaAsRevision() throws Exception {
        try (StubSkillServer stub = stubRepository()) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);

            ExternalAiSkillDocument document = client.fetch("npx skills add anthropics/skills@pdf");

            assertThat(document.markdown()).isEqualTo(PDF_SKILL);
            assertThat(document.revision()).isEqualTo("blobsha1234567");
            assertThat(document.fallbackName()).isEqualTo("pdf");
            assertThat(document.reference()).isEqualTo(stub.baseUrl() + "/anthropics/skills/tree/main/skills/pdf");
            assertThat(document.sourceUrl()).isEqualTo("https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md");
        }
    }

    @Test
    void theStoredReferenceFetchesAgainWithoutListingTheRepository() throws Exception {
        try (StubSkillServer stub = stubRepository()) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);
            String reference = client.fetch("anthropics/skills@pdf").reference();
            stub.requests.clear();

            client.fetch(reference);

            assertThat(stub.requests).containsExactly("/repos/anthropics/skills/contents/skills/pdf/SKILL.md?ref=main");
        }
    }

    @Test
    void aRepositoryWithSeveralSkillsNeedsAName() throws Exception {
        try (StubSkillServer stub = stubRepository()) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);

            ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class, () -> client.fetch("anthropics/skills"));

            assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.INVALID_REFERENCE);
            assertThat(e.detail()).contains("2 skills");
        }
    }

    @Test
    void anUnknownSkillNameIsNotFound() throws Exception {
        try (StubSkillServer stub = stubRepository()) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);

            ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class, () -> client.search("anthropics/skills@xlsx"));

            assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.NOT_FOUND);
        }
    }

    @Test
    void sendsTheTokenAndTheApiVersionHeaders() throws Exception {
        try (StubSkillServer stub = stubRepository()) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(),
                new ExternalAiSkillHttp.Credentials(AiSkillProviderAuth.TOKEN, null, "ghp_test"));

            client.fetch("anthropics/skills/skills/pdf");

            assertThat(stub.authorizations).containsExactly("Bearer ghp_test", "Bearer ghp_test");
        }
    }

    @Test
    void anExhaustedQuotaIsReportedAsRateLimitAndAMissingRepositoryAsNotFound() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()
                .reply("/repos/o/limited", new StubSkillServer.Reply(403, "{}", Map.of("x-ratelimit-remaining", "0")))) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);

            assertThat(expectThrows(ExternalAiSkillException.class, () -> client.search("o/limited")).reason())
                .isEqualTo(ExternalAiSkillException.Reason.RATE_LIMITED);
            assertThat(expectThrows(ExternalAiSkillException.class, () -> client.search("o/missing")).reason())
                .isEqualTo(ExternalAiSkillException.Reason.NOT_FOUND);
        }
    }

    @Test
    void refusesASkillFileAboveTheSizeCap() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()
                .reply("/repos/o/r/contents/big/SKILL.md?ref=main", StubSkillServer.Reply.json(
                    "{\"type\":\"file\",\"sha\":\"x\",\"size\":" + (ExternalAiSkillHttp.MAX_SKILL_CHARS + 1) + ",\"content\":\"\"}"))) {
            GitHubAiSkillClient client = new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE);

            ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class,
                () -> client.fetch(stub.baseUrl() + "/o/r/tree/main/big"));

            assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.TOO_LARGE);
        }
    }

    @Test
    void testConnectionReportsTheRemainingQuota() throws Exception {
        try (StubSkillServer stub = new StubSkillServer().reply("/rate_limit", new StubSkillServer.Reply(200, "{}",
                Map.of("x-ratelimit-remaining", "4999", "x-ratelimit-limit", "5000")))) {
            assertThat(new GitHubAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE).testConnection())
                .isEqualTo("4999/5000");
        }
    }

    @Test
    void enterpriseApiUrlsMapToTheirWebHost() {
        assertThat(GitHubAiSkillClient.webBaseUrlFor("https://api.github.com")).isEqualTo("https://github.com");
        assertThat(GitHubAiSkillClient.webBaseUrlFor("https://git.example.com/api/v3")).isEqualTo("https://git.example.com");
    }
}
