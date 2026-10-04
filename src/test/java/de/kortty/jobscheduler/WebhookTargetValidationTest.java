package de.kortty.jobscheduler;

import de.kortty.jobscheduler.WebhookUrlValidator.Problem;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;

class WebhookTargetValidationTest {

    private static Problem problem(String url) {
        return WebhookUrlValidator.validate(url).problem();
    }

    @Test
    void acceptsHttpsAndLoopbackHttp() {
        assertThat(WebhookUrlValidator.validate("https://hooks.slack.com/services/T0/B0/xyz").valid()).isTrue();
        assertThat(WebhookUrlValidator.validate("HTTPS://example.org:8443/hook?x=1").valid()).isTrue();
        assertThat(WebhookUrlValidator.validate("http://localhost:8080/hook").valid()).isTrue();
        assertThat(WebhookUrlValidator.validate("http://127.0.0.1:9000/hook").valid()).isTrue();
        assertThat(WebhookUrlValidator.validate("http://[::1]:9000/hook").valid()).isTrue();
    }

    @Test
    void rejectsHttpOnANonLoopbackHost() {
        assertThat(problem("http://hooks.slack.com/services/T0/B0/xyz")).isEqualTo(Problem.INSECURE_HTTP);
        assertThat(problem("http://10.0.0.5/hook")).isEqualTo(Problem.INSECURE_HTTP);
        assertThat(problem("http://127.0.0.1.attacker.net/hook")).isEqualTo(Problem.INSECURE_HTTP);
        assertThat(problem("http://localhost.attacker.net/hook")).isEqualTo(Problem.INSECURE_HTTP);
    }

    @Test
    void rejectsUserInfo() {
        assertThat(problem("https://user:secret@example.org/hook")).isEqualTo(Problem.USERINFO);
        assertThat(problem("https://token@example.org/hook")).isEqualTo(Problem.USERINFO);
        assertThat(problem("http://user@localhost/hook")).isEqualTo(Problem.USERINFO);
    }

    @Test
    void rejectsOtherSchemes() {
        assertThat(problem("javascript:alert(1)")).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(problem("file:///etc/passwd")).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(problem("ftp://example.org/hook")).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(problem("//example.org/hook")).isEqualTo(Problem.UNSUPPORTED_SCHEME);
        assertThat(problem("example.org/hook")).isEqualTo(Problem.UNSUPPORTED_SCHEME);
    }

    @Test
    void rejectsEmptyMalformedAndHostless() {
        assertThat(problem(null)).isEqualTo(Problem.EMPTY);
        assertThat(problem("   ")).isEqualTo(Problem.EMPTY);
        assertThat(problem("https://exa mple.org/hook")).isEqualTo(Problem.MALFORMED);
        assertThat(problem("https://example.org/ho\nok")).isEqualTo(Problem.MALFORMED);
        assertThat(problem("https:///hook")).isEqualTo(Problem.MISSING_HOST);
    }

    @Test
    void hostIsLowerCasedAndEmptyForInvalidUrls() {
        assertThat(WebhookUrlValidator.host("https://Hooks.Slack.com/services/a/b/c")).hasValue("hooks.slack.com");
        assertThat(WebhookUrlValidator.host("javascript:alert(1)")).isEmpty();
    }

    @Test
    void everyProblemHasAMessageInEveryBundle() throws Exception {
        for (String bundle : List.of("messages.properties", "messages_de.properties", "messages_es.properties",
            "messages_fr.properties", "messages_hr.properties", "messages_it.properties",
            "messages_nl.properties", "messages_pt.properties")) {
            Properties properties = new Properties();
            try (InputStream in = WebhookTargetValidationTest.class.getResourceAsStream("/i18n/" + bundle)) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (Problem problem : Problem.values()) {
                assertThat(properties.getProperty(problem.i18nKey())).isNotEmpty();
            }
        }
    }
}
