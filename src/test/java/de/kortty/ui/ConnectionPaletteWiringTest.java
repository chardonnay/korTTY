package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How the command palette reaches the connections, pinned against the source because MainWindow
 * cannot be built without a stage: a chosen connection signs in through connectSavedConnection,
 * the Connection Manager's one sign-in flow with the policy check before any prompt, and counts as
 * a use of it, while the Connection Manager itself still does not; the teamwork connections are
 * asked for only while the policy allows teamwork and lose the recycle bin's; and the blocked
 * targets come from the server policy.
 */
class ConnectionPaletteWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path ROWS = Path.of("src/main/java/de/kortty/ui/ConnectionPaletteRows.java");

    @Test
    void aChosenConnectionSignsInLikeTheConnectionManagerAndCountsAsUsed() throws IOException {
        String source = source(MAIN_WINDOW);

        assertThat(methodBody(source, "private void showCommandPalette() {"))
            .contains("ConnectionPaletteRows.source(app,\n"
                + "                        connection -> connectSavedConnection(connection, true, tab -> { }))");
        assertWithMessage("the Connection Manager keeps not counting its connects")
            .that(methodBody(source, "private void showConnectionManager() {"))
            .contains("connectSavedConnection(connection, false, tab -> { })");
        String connect = methodBody(source, "private ConnectionAuthResolver.Status connectSavedConnection(");
        assertThat(connect).contains("resolveConnectionAuthInteractively(connection)");
        assertThat(connect).contains("if (recordUsage) {");
    }

    @Test
    void teamworkConnectionsDependOnThePolicyAndSkipTheRecycleBin() throws IOException {
        String rows = methodBody(source(ROWS),
            "static ConnectionPaletteSource source(KorTTYApplication app, Consumer<ServerConnection> connect) {");

        assertThat(rows).contains("() -> app.getConfigManager().getConnections(),");
        assertThat(rows).contains("() -> PolicyManager.effective().teamworkAllowed(),");
        assertThat(rows).contains("() -> teamworkConnections(app),");
        assertThat(rows).contains("() -> deletedTeamworkIds(app)),");
        assertThat(rows).contains("ServerAccessPolicy::firstBlockedTarget,");
        assertThat(source(ROWS)).contains("recycleBin.getDeletedIds()");
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
