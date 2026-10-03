package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Quick Connect's Open Group used to skip every member without a stored vault password, so SSH key
 * and local-shell members never opened. The rule itself is covered by {@link GroupOpenSupportTest};
 * opening a group needs a live stage and connector, so this pins how
 * {@code MainWindow.openGroupConnections} uses it: every member goes through
 * {@link GroupOpenSupport#decide}, a skipped one is named to the user, and the status bar counts the
 * tabs that opened.
 */
class OpenGroupConnectionsWiringTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/MainWindow.java");

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_it.properties",
            "messages_es.properties",
            "messages_pt.properties",
            "messages_fr.properties",
            "messages_hr.properties",
            "messages_nl.properties");

    @Test
    void everyMemberIsDecidedByTheSharedRuleBeforeItsTabIsBuilt() throws IOException {
        String group = methodBody(source(), "private void openGroupConnections(");

        // The loop may run over a policy-filtered list; what matters is what happens inside it.
        int loop = group.indexOf("for (ServerConnection conn : ");
        assertThat(loop).isAtLeast(0);
        int decide = group.indexOf("GroupOpenSupport.decide(conn, this::getConnectionPassword)");
        assertWithMessage("openGroupConnections decides each member with GroupOpenSupport")
            .that(decide).isGreaterThan(loop);
        int skip = group.indexOf("if (!decision.open())");
        assertThat(skip).isGreaterThan(decide);
        int skipEnd = group.indexOf("continue;", skip);
        assertThat(skipEnd).isGreaterThan(skip);

        // Only a member that opens reaches the usage count and a tab, with the decided password.
        int password = group.indexOf("String password = decision.password();");
        assertThat(password).isGreaterThan(skipEnd);
        assertThat(group.indexOf("incrementUsageCount()")).isGreaterThan(skipEnd);
        assertThat(group.indexOf("new TerminalTab(conn, password)")).isGreaterThan(password);

        // The old blanket "no password, skip" check, which also skipped key and local-shell
        // members, must not come back.
        assertThat(group).doesNotContain("vault.retrievePassword(");
        assertThat(group).doesNotContain("password == null || password.isEmpty()");
    }

    @Test
    void skippedMembersAreNamedToTheUserAndTheStatusCountsOpenedTabs() throws IOException {
        String source = source();
        String group = methodBody(source, "private void openGroupConnections(");

        int skip = group.indexOf("if (!decision.open())");
        assertThat(group.indexOf("skippedWithoutPassword.add(conn.getDisplayName())", skip))
            .isGreaterThan(skip);
        int connect = group.indexOf("tab.connect();");
        assertThat(group.indexOf("opened++;", connect)).isGreaterThan(connect);
        assertThat(group).contains("I18n.get(\"status.groupOpened\", groupName, opened)");
        assertThat(group).doesNotContain("groupConnections.size()));");
        assertThat(group).contains("showGroupMembersSkippedWithoutPassword(groupName, skippedWithoutPassword)");

        String notice = methodBody(source, "private void showGroupMembersSkippedWithoutPassword(");
        assertThat(notice).contains("I18n.get(\"quickConnect.groupSkipped.header\", skipped.size(), groupName)");
        assertThat(notice).contains("I18n.get(\"quickConnect.groupSkipped.content\",");
    }

    @Test
    void theSkippedNoticeIsTranslatedEverywhereAndKeepsItsPlaceholders() throws IOException {
        for (String bundle : BUNDLES) {
            Properties messages = loadBundle(bundle);
            String header = messages.getProperty("quickConnect.groupSkipped.header");
            String content = messages.getProperty("quickConnect.groupSkipped.content");
            assertWithMessage(bundle + " header").that(header).isNotNull();
            assertWithMessage(bundle + " content").that(content).isNotNull();
            assertWithMessage(bundle + " header names the count").that(header).contains("{0}");
            assertWithMessage(bundle + " header names the group").that(header).contains("{1}");
            assertWithMessage(bundle + " content lists the members").that(content).contains("{0}");
        }
    }

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private Properties loadBundle(String fileName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }

    /** The body of the method declared with {@code declaration}, up to its matching brace. */
    private static String methodBody(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertWithMessage("declaration %s", declaration).that(start).isAtLeast(0);
        assertWithMessage("declaration %s is ambiguous", declaration)
            .that(source.indexOf(declaration, start + 1)).isEqualTo(-1);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i, c);
            } else if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i) + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        throw new AssertionError("unterminated method " + declaration);
    }

    /** Index of the quote that closes the string or char literal opened at {@code start}. */
    private static int endOfLiteral(String source, int start, char quote) {
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        throw new AssertionError("unterminated literal at " + start);
    }
}
