package de.kortty.core;

import de.kortty.model.AiSkillProviderAuth;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SkillsMpAiSkillClientTest {

    /** The shape of a real SkillsMP v1 search response (trimmed). */
    private static final String SEARCH_RESPONSE = "{\"success\":true,\"data\":{\"skills\":["
        + "{\"id\":\"a\",\"name\":\"kubernetes-patterns\",\"author\":\"affaan-m\",\"description\":\"Kubernetes workload patterns.\","
        + "\"githubUrl\":\"https://github.com/affaan-m/ECC/tree/main/skills/kubernetes-patterns\","
        + "\"skillUrl\":\"https://skillsmp.com/creators/affaan-m/ecc/skills-kubernetes-patterns\",\"stars\":269367,\"updatedAt\":1786507094},"
        + "{\"id\":\"b\",\"name\":\"no-github\",\"author\":\"x\",\"description\":\"\",\"skillUrl\":\"https://skillsmp.com/x\",\"stars\":1,\"updatedAt\":1}"
        + "],\"pagination\":{\"page\":1,\"limit\":30,\"total\":2,\"hasNext\":false}}}";

    /** Records what SkillsMP hands over for download. */
    private static final class RecordingGitHub implements ExternalAiSkillClient {
        final List<String> fetched = new ArrayList<>();

        @Override
        public List<ExternalAiSkillCandidate> search(String query) {
            return List.of();
        }

        @Override
        public ExternalAiSkillDocument fetch(String reference) {
            fetched.add(reference);
            return new ExternalAiSkillDocument(reference, reference, "sha", "# skill", "skill");
        }

        @Override
        public String testConnection() {
            return "";
        }
    }

    @Test
    void searchMapsHitsToTheirGitHubDirectoryAndSkipsHitsWithoutOne() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()
                .reply("/api/v1/skills/search?q=kubernetes%20pods&limit=30", StubSkillServer.Reply.json(SEARCH_RESPONSE))) {
            SkillsMpAiSkillClient client = new SkillsMpAiSkillClient(stub.baseUrl(),
                new ExternalAiSkillHttp.Credentials(AiSkillProviderAuth.TOKEN, null, "sk_live_test"), new RecordingGitHub());

            List<ExternalAiSkillCandidate> candidates = client.search("kubernetes pods");

            assertThat(candidates).containsExactly(new ExternalAiSkillCandidate(
                "https://github.com/affaan-m/ECC/tree/main/skills/kubernetes-patterns",
                "kubernetes-patterns",
                "Kubernetes workload patterns.",
                "affaan-m",
                "https://skillsmp.com/creators/affaan-m/ecc/skills-kubernetes-patterns",
                269367));
            assertThat(stub.authorizations).containsExactly("Bearer sk_live_test");
        }
    }

    @Test
    void downloadsGoThroughTheGitHubClient() throws Exception {
        RecordingGitHub github = new RecordingGitHub();
        SkillsMpAiSkillClient client = new SkillsMpAiSkillClient("https://skillsmp.com", ExternalAiSkillHttp.Credentials.NONE, github);

        client.fetch("https://github.com/o/r/tree/main/s");

        assertThat(github.fetched).containsExactly("https://github.com/o/r/tree/main/s");
    }

    @Test
    void theVendorQuotaCodesAreRateLimits() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()
                .reply("/api/v1/skills/search?q=pdf&limit=30", new StubSkillServer.Reply(429,
                    "{\"success\":false,\"error\":{\"code\":\"DAILY_QUOTA_EXCEEDED\",\"message\":\"Daily quota exceeded\"}}", Map.of()))) {
            SkillsMpAiSkillClient client = new SkillsMpAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE, new RecordingGitHub());

            ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class, () -> client.search("pdf"));

            assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.RATE_LIMITED);
            assertThat(e.detail()).contains("DAILY_QUOTA_EXCEEDED");
        }
    }

    @Test
    void anEmptyQueryIsNotSent() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()) {
            SkillsMpAiSkillClient client = new SkillsMpAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE, new RecordingGitHub());

            assertThat(expectThrows(ExternalAiSkillException.class, () -> client.search("  ")).reason())
                .isEqualTo(ExternalAiSkillException.Reason.INVALID_REFERENCE);
            assertThat(stub.requests).isEmpty();
        }
    }

    @Test
    void testConnectionReportsTheDailyQuota() throws Exception {
        try (StubSkillServer stub = new StubSkillServer().reply("/api/v1/skills/search?q=git&limit=1",
                new StubSkillServer.Reply(200, SEARCH_RESPONSE,
                    Map.of("x-ratelimit-daily-remaining", "48", "x-ratelimit-daily-limit", "50")))) {
            assertThat(new SkillsMpAiSkillClient(stub.baseUrl(), ExternalAiSkillHttp.Credentials.NONE, new RecordingGitHub())
                .testConnection()).isEqualTo("48/50");
        }
    }
}
