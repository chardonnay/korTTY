package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SnippetManagerChangeListenerTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-change-listener-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    private static List<SnippetManager.Change> record(SnippetManager manager) {
        List<SnippetManager.Change> changes = new ArrayList<>();
        manager.addChangeListener(changes::add);
        return changes;
    }

    @Test
    void firesAfterSuccessfulSaveAndAfterLoad() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        List<SnippetManager.Change> changes = record(manager);

        manager.addSnippet(new Snippet("a.sh", "echo a", "bash"));
        assertThat(changes).isEmpty(); // mutation alone is not persisted, so nothing is announced

        manager.save();
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().reloaded()).isFalse();
        assertThat(changes.getFirst().removedSnippetIds()).isEmpty();

        manager.load();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).reloaded()).isTrue();

        SnippetManager fresh = new SnippetManager(tempDir.resolve("does-not-exist"));
        List<SnippetManager.Change> freshChanges = record(fresh);
        fresh.load();
        assertThat(freshChanges).hasSize(1);
        assertThat(freshChanges.getFirst().reloaded()).isTrue();
    }

    @Test
    void firesOnTheCallingThread() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        List<Thread> callers = new ArrayList<>();
        manager.addChangeListener(change -> callers.add(Thread.currentThread()));

        manager.save();

        assertThat(callers).containsExactly(Thread.currentThread());
    }

    @Test
    void doesNotFireAfterFailedSave() throws Exception {
        Path notADirectory = tempDir.resolve("blocker");
        Files.writeString(notADirectory, "a regular file where the config directory should be");
        SnippetManager manager = new SnippetManager(notADirectory);
        List<SnippetManager.Change> changes = record(manager);

        expectThrows(Exception.class, manager::save);

        assertThat(changes).isEmpty();
    }

    @Test
    void removedIdsAreReportedWithTheNextSuccessfulSaveExactlyOnce() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet keep = new Snippet("keep.sh", "echo keep", "bash");
        Snippet gone = new Snippet("gone.sh", "echo gone", "bash");
        manager.addSnippet(keep);
        manager.addSnippet(gone);
        manager.save();
        List<SnippetManager.Change> changes = record(manager);

        manager.removeSnippet(gone);
        assertThat(changes).isEmpty();

        manager.save();
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().removedSnippetIds()).containsExactly(gone.getId());

        manager.save();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).removedSnippetIds()).isEmpty();
    }

    @Test
    void removingAnUnknownSnippetReportsNothing() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        List<SnippetManager.Change> changes = record(manager);

        manager.removeSnippet(new Snippet("never-added.sh", "echo x", "bash"));
        manager.save();

        assertThat(changes.getFirst().removedSnippetIds()).isEmpty();
    }

    @Test
    void reloadDropsPendingRemovals() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("a.sh", "echo a", "bash");
        manager.addSnippet(snippet);
        manager.save();
        List<SnippetManager.Change> changes = record(manager);

        manager.removeSnippet(snippet);
        manager.load(); // the removal never reached the disk and the list was replaced from it
        manager.save();

        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).reloaded()).isTrue();
        assertThat(changes.get(1).removedSnippetIds()).isEmpty();
        assertThat(manager.findById(snippet.getId())).isPresent();
    }

    @Test
    void throwingListenerDoesNotStopTheOthersOrTheSave() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        List<String> calls = new ArrayList<>();
        manager.addChangeListener(change -> {
            calls.add("first");
            throw new IllegalStateException("listener bug");
        });
        manager.addChangeListener(change -> calls.add("second"));

        manager.save();

        assertThat(calls).containsExactly("first", "second").inOrder();
        assertThat(Files.exists(tempDir.resolve("snippets.xml"))).isTrue();
    }

    @Test
    void removedListenerIsNotCalledAgain() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        List<SnippetManager.Change> changes = new ArrayList<>();
        Consumer<SnippetManager.Change> listener = changes::add;
        manager.addChangeListener(listener);
        manager.save();
        manager.removeChangeListener(listener);

        manager.save();

        assertThat(changes).hasSize(1);
    }

    @Test
    void changeCopiesItsIdSetDefensively() {
        Set<String> ids = new java.util.HashSet<>(Set.of("a"));
        SnippetManager.Change change = new SnippetManager.Change(ids, false);
        ids.add("b");

        assertThat(change.removedSnippetIds()).containsExactly("a");
        assertThat(new SnippetManager.Change(null, true).removedSnippetIds()).isEmpty();
    }

    @Test
    void ensureCategoryIsIdempotentAndCaseInsensitiveLikeTheLookup() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        manager.load();
        int before = manager.getAllCategories().size();

        assertThat(manager.ensureCategory("Deploy")).isTrue();
        assertThat(manager.ensureCategory("Deploy")).isFalse();
        assertThat(manager.ensureCategory("deploy")).isFalse();
        assertThat(manager.ensureCategory("  DEPLOY  ")).isFalse();
        assertThat(manager.ensureCategory(null)).isFalse();
        assertThat(manager.ensureCategory("   ")).isFalse();
        // The fixed Script-Header category is seeded by load() and must not be duplicated.
        assertThat(manager.ensureCategory(SnippetManager.SCRIPT_HEADER_CATEGORY.toLowerCase())).isFalse();

        List<String> names = manager.getAllCategories().stream().map(SnippetCategory::getName).toList();
        assertThat(names).hasSize(before + 1);
        assertThat(names).contains("Deploy");
        assertThat(manager.findCategoryByName("DEPLOY")).isPresent();
    }

    @Test
    void ensureCategoryTrimsTheStoredName() {
        SnippetManager manager = new SnippetManager(tempDir);

        assertThat(manager.ensureCategory("  Ops ")).isTrue();

        assertThat(manager.getAllCategories().stream().map(SnippetCategory::getName).toList()).containsExactly("Ops");
    }
}
