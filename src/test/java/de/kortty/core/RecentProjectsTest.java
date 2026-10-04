package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The projects <i>File → Open Recent</i> lists: the project files opened or saved last, newest first
 * and at most ten, then the projects of korTTY's project folder, the one changed last first. Files
 * that no longer exist are left out, and <i>Clear List</i> keeps older folder projects out too.
 */
class RecentProjectsTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-recent-projects").toAbsolutePath().normalize();
    }

    @AfterMethod(alwaysRun = true)
    void deleteDir() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---- remember -----------------------------------------------------------------------------

    @Test
    void aProjectOpenedOrSavedMovesToTheFront() {
        Path a = dir.resolve("a.kortty");
        Path b = dir.resolve("b.kortty");
        Path c = dir.resolve("c.kortty");

        List<String> recent = RecentProjects.remember(List.of(), a);
        recent = RecentProjects.remember(recent, b);
        recent = RecentProjects.remember(recent, c);
        recent = RecentProjects.remember(recent, a);

        assertThat(recent).containsExactly(a.toString(), c.toString(), b.toString()).inOrder();
    }

    @Test
    void theSameFileIsRememberedOnceHoweverItsPathIsWritten() {
        Path project = dir.resolve("ops.kortty");
        Path roundabout = dir.resolve("sub").resolve("..").resolve("ops.kortty");

        List<String> recent = RecentProjects.remember(List.of(project.toString()), roundabout);

        assertThat(recent).containsExactly(project.toString());
    }

    @Test
    void theListIsBoundedToTen() {
        List<String> recent = List.of();
        for (int i = 1; i <= 14; i++) {
            recent = RecentProjects.remember(recent, dir.resolve("p" + i + ".kortty"));
        }

        assertThat(recent).hasSize(RecentProjects.MAX_ENTRIES);
        assertThat(RecentProjects.MAX_ENTRIES).isEqualTo(10);
        assertThat(recent.get(0)).isEqualTo(dir.resolve("p14.kortty").toString());
        assertWithMessage("the oldest entries fall off the end")
            .that(recent.get(9)).isEqualTo(dir.resolve("p5.kortty").toString());
    }

    @Test
    void rememberingStoresAnAbsolutePathAndDropsBlankEntriesWithoutChangingTheInput() {
        List<String> stored = new ArrayList<>(Arrays.asList("", "   ", null, dir.resolve("old.kortty").toString()));

        List<String> recent = RecentProjects.remember(stored, Path.of("relative.kortty"));

        assertThat(Path.of(recent.get(0)).isAbsolute()).isTrue();
        assertThat(recent).containsExactly(Path.of("relative.kortty").toAbsolutePath().normalize().toString(),
            dir.resolve("old.kortty").toString()).inOrder();
        assertWithMessage("remember returns a new list").that(stored).hasSize(4);
    }

    // ---- list ---------------------------------------------------------------------------------

    @Test
    void missingFilesAreFilteredOut() throws IOException {
        Path kept = project("kept.kortty", 1_000L);
        Path deleted = dir.resolve("deleted.kortty");
        Path folder = Files.createDirectory(dir.resolve("folder.kortty"));

        List<Path> listed = RecentProjects.list(
            List.of(deleted.toString(), kept.toString(), folder.toString()), List.of(), 0L, 10);

        assertThat(listed).containsExactly(kept);
    }

    @Test
    void rememberedProjectsComeFirstThenFolderProjectsChangedLastFirst() throws IOException {
        Path rememberedOld = project("remembered-old.kortty", 1_000L);
        Path rememberedNew = project("remembered-new.kortty", 500L);
        Path folderOlder = project("folder-older.kortty", 2_000L);
        Path folderNewer = project("folder-newer.kortty", 9_000L);

        List<Path> listed = RecentProjects.list(
            List.of(rememberedNew.toString(), rememberedOld.toString()),
            List.of(folderOlder, rememberedOld, folderNewer), 0L, 10);

        assertWithMessage("the remembered order wins over the file times, and a remembered folder project is "
            + "listed once").that(listed)
            .containsExactly(rememberedNew, rememberedOld, folderNewer, folderOlder).inOrder();
    }

    @Test
    void theListStopsAtTheMaximum() throws IOException {
        List<Path> folder = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            folder.add(project("p" + i + ".kortty", i * 1_000L));
        }

        List<Path> listed = RecentProjects.list(List.of(), folder, 0L, RecentProjects.MAX_ENTRIES);

        assertThat(listed).hasSize(10);
        assertThat(listed.get(0)).isEqualTo(dir.resolve("p12.kortty"));
        assertThat(RecentProjects.list(List.of(), folder, 0L, 0)).isEmpty();
    }

    @Test
    void clearListKeepsFolderProjectsChangedBeforeItOut() throws IOException {
        Path before = project("before.kortty", 1_000L);
        Path after = project("after.kortty", 5_000L);
        Path remembered = project("remembered.kortty", 100L);

        List<Path> listed = RecentProjects.list(List.of(remembered.toString()), List.of(before, after), 2_000L, 10);

        assertWithMessage("a project opened or saved after Clear List is remembered and listed whatever its file "
            + "time").that(listed).containsExactly(remembered, after).inOrder();
    }

    @Test
    void malformedEntriesAreSkipped() throws IOException {
        Path kept = project("kept.kortty", 1_000L);

        List<Path> listed = RecentProjects.list(Arrays.asList(null, "", "  ", "bad\u0000path", kept.toString()),
            Arrays.asList(null, kept), 0L, 10);

        assertThat(listed).containsExactly(kept);
    }

    // ---- label --------------------------------------------------------------------------------

    @Test
    void theLabelNamesTheProjectAndItsFolderWithTheHomeFolderAsTilde() {
        Path home = dir;
        String separator = dir.getFileSystem().getSeparator();

        assertThat(RecentProjects.label(home.resolve("work").resolve("ops.kortty"), home))
            .isEqualTo("ops — ~" + separator + "work");
        assertThat(RecentProjects.label(home.resolve("ops.KORTTY"), home)).isEqualTo("ops — ~");
        Path elsewhere = dir.getRoot().resolve("srv").resolve("ops.kortty");
        assertThat(RecentProjects.label(elsewhere, home)).isEqualTo("ops — " + elsewhere.getParent());
        assertWithMessage("a file called only .kortty keeps its name")
            .that(RecentProjects.label(home.resolve(".kortty"), null)).startsWith(".kortty — ");
    }

    @Test
    void aLongFolderIsShortenedAtTheFront() {
        Path deep = dir.getRoot();
        for (int i = 0; i < 12; i++) {
            deep = deep.resolve("folder" + i);
        }

        String label = RecentProjects.label(deep.resolve("ops.kortty"), null);

        String folder = label.substring(label.indexOf(" — ") + 3);
        assertThat(folder).startsWith("…");
        assertThat(folder.length()).isEqualTo(60);
        assertThat(folder).endsWith("folder11");
    }

    private Path project(String name, long modifiedMillis) throws IOException {
        Path file = Files.writeString(dir.resolve(name), "<project/>");
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
        return file;
    }
}
