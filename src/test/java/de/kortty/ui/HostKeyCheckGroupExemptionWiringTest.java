package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where the Connection Manager keeps the folders without host-key verification in step with its
 * folders, which no headless test can build: renaming a folder moves the exemptions with the
 * connections, deleting it drops them, and the result is saved. The rules themselves are checked in
 * {@code HostKeyCheckGroupExemptionsTest}.
 */
class HostKeyCheckGroupExemptionWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void renamingAFolderMovesItsExemptions() throws IOException {
        String rename = region(source("ConnectionManagerDialog.java"),
            "private void renameGroup(GroupPath oldPath) {", "\n    }\n");

        int computed = rename.indexOf("HostKeyCheckGroupExemptions.renamed(");
        int moved = rename.indexOf("conn.setGroup(");
        int stored = rename.indexOf("storeHostKeyCheckExemptions(exemptionsAfter);");
        assertThat(computed).isAtLeast(0);
        assertWithMessage("the folders that exist are read before the connections move")
            .that(moved).isGreaterThan(computed);
        assertThat(stored).isGreaterThan(moved);
        assertThat(rename).contains("oldPath.getPath(), newPath.getPath(), "
            + "connections.stream().map(ServerConnection::getGroup).toList()");
    }

    @Test
    void deletingAFolderDropsItsExemptions() throws IOException {
        String delete = region(source("ConnectionManagerDialog.java"),
            "private void deleteGroup(GroupPath groupPath) {", "\n    }\n");

        int removed = delete.indexOf("configManager.removeConnection(conn);");
        int stored = delete.indexOf("storeHostKeyCheckExemptions(hostKeyCheckExemptionsAfter(");
        assertThat(removed).isAtLeast(0);
        assertThat(stored).isGreaterThan(removed);
        assertThat(delete).contains("HostKeyCheckGroupExemptions.deleted(exempt, groupPath.getPath())");
    }

    @Test
    void theNewExemptionsAreSaved() throws IOException {
        String store = region(source("ConnectionManagerDialog.java"),
            "private void storeHostKeyCheckExemptions(List<String> exempt) {", "\n    }\n");

        int set = store.indexOf("settings.setHostKeyCheckDisabledGroups(new ArrayList<>(exempt));");
        int write = store.indexOf("gsm.save();");
        assertThat(set).isAtLeast(0);
        assertThat(write).isGreaterThan(set);
        assertWithMessage("a failed save is reported, not swallowed")
            .that(store).contains("alert.showAndWait();");
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
