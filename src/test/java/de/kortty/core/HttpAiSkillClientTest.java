package de.kortty.core;

import de.kortty.model.AiSkillProviderAuth;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class HttpAiSkillClientTest {

    @Test
    void fetchesARelativePathWithBasicAuthAndHashesTheBodyAsRevision() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()
                .reply("/skills/review/SKILL.md", new StubSkillServer.Reply(200, "# Review\nBe kind.", Map.of()))) {
            HttpAiSkillClient client = new HttpAiSkillClient(stub.baseUrl() + "/",
                new ExternalAiSkillHttp.Credentials(AiSkillProviderAuth.BASIC, "alice", "s3cret"));

            ExternalAiSkillDocument document = client.fetch("skills/review/SKILL.md");

            assertThat(document.markdown()).isEqualTo("# Review\nBe kind.");
            assertThat(document.reference()).isEqualTo(stub.baseUrl() + "/skills/review/SKILL.md");
            assertThat(document.revision()).isEqualTo(ExternalAiSkillHttp.sha256("# Review\nBe kind."));
            assertThat(document.fallbackName()).isEqualTo("review");
            assertThat(stub.authorizations).containsExactly(
                "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void plainHttpIsOnlyAllowedToThisMachine() {
        HttpAiSkillClient client = new HttpAiSkillClient("", ExternalAiSkillHttp.Credentials.NONE);

        ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class,
            () -> client.fetch("http://skills.example.com/a/SKILL.md"));

        assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.INSECURE_URL);
    }

    @Test
    void redirectsAreNotFollowedSoCredentialsStayWithTheConfiguredHost() throws Exception {
        try (StubSkillServer stub = new StubSkillServer()
                .reply("/moved/SKILL.md", new StubSkillServer.Reply(302, "", Map.of("Location", "https://elsewhere.example/x")))) {
            HttpAiSkillClient client = new HttpAiSkillClient(stub.baseUrl(),
                new ExternalAiSkillHttp.Credentials(AiSkillProviderAuth.TOKEN, null, "t"));

            ExternalAiSkillException e = expectThrows(ExternalAiSkillException.class, () -> client.fetch("moved/SKILL.md"));

            assertThat(e.reason()).isEqualTo(ExternalAiSkillException.Reason.HTTP_ERROR);
            assertThat(e.detail()).contains("https://elsewhere.example/x");
            assertThat(stub.requests).containsExactly("/moved/SKILL.md");
        }
    }

    @Test
    void searchResolvesTheEnteredUrlToOneCandidate() throws Exception {
        HttpAiSkillClient client = new HttpAiSkillClient("https://intranet.example/skills", ExternalAiSkillHttp.Credentials.NONE);

        assertThat(client.search("ops/deploy.md")).containsExactly(new ExternalAiSkillCandidate(
            "https://intranet.example/skills/ops/deploy.md", "deploy", "", "intranet.example",
            "https://intranet.example/skills/ops/deploy.md", null));
    }

    @Test
    void aRelativeReferenceNeedsABaseUrl() {
        HttpAiSkillClient client = new HttpAiSkillClient(null, ExternalAiSkillHttp.Credentials.NONE);

        assertThat(expectThrows(ExternalAiSkillException.class, () -> client.search("a/SKILL.md")).reason())
            .isEqualTo(ExternalAiSkillException.Reason.INVALID_REFERENCE);
    }
}
