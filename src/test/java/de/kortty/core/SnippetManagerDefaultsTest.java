package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class SnippetManagerDefaultsTest {

    @Test
    void seedsScriptHeaderCategoryAndDefaultOperatingSystems() throws Exception {
        Path dir = Files.createTempDirectory("kortty-snippets");
        SnippetManager manager = new SnippetManager(dir);
        manager.load();

        assertThat(manager.findCategoryByName(SnippetManager.SCRIPT_HEADER_CATEGORY).isPresent()).isTrue();
        assertThat(manager.getOperatingSystems()).containsAtLeast("Windows", "MacOS", "Linux");
        assertThat(SnippetManager.isFixedCategory("script-header")).isTrue();
        assertThat(SnippetManager.isFixedCategory("Other")).isFalse();
    }

    @Test
    void operatingSystemFieldAndOsListPersistAcrossReload() throws Exception {
        Path dir = Files.createTempDirectory("kortty-snippets");
        SnippetManager manager = new SnippetManager(dir);
        manager.load();

        Snippet header = new Snippet("daniel_std", "# Author: ${username}", "bash");
        header.setOperatingSystem("Linux");
        header.setCategory(SnippetManager.SCRIPT_HEADER_CATEGORY);
        manager.addSnippet(header);
        manager.addOperatingSystem("FreeBSD");
        manager.save();

        SnippetManager reloaded = new SnippetManager(dir);
        reloaded.load();
        Snippet loaded = reloaded.findById(header.getId()).orElseThrow();
        assertThat(loaded.getOperatingSystem()).isEqualTo("Linux");
        assertThat(reloaded.getOperatingSystems()).contains("FreeBSD");
        assertThat(reloaded.getScriptHeaderSnippets()).hasSize(1);
        assertThat(reloaded.getScriptHeaderSnippets().get(0).getName()).isEqualTo("daniel_std");
    }

    @Test
    void fixedScriptHeaderCategoryCannotBeRemoved() throws Exception {
        Path dir = Files.createTempDirectory("kortty-snippets");
        SnippetManager manager = new SnippetManager(dir);
        manager.load();

        var category = manager.findCategoryByName(SnippetManager.SCRIPT_HEADER_CATEGORY).orElseThrow();
        manager.removeCategory(category);
        assertThat(manager.findCategoryByName(SnippetManager.SCRIPT_HEADER_CATEGORY).isPresent()).isTrue();
    }
}
