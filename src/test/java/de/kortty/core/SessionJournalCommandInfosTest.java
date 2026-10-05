package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalCommandInfosTest {

    private static Snippet snippet(String name, String fileName) {
        Snippet snippet = new Snippet();
        snippet.setName(name);
        snippet.setFileName(fileName);
        return snippet;
    }

    @Test
    void matchesASnippetByFileNameWithOrWithoutPathAndExtension() {
        List<Snippet> snippets = List.of(snippet("Deploy web01", "deploy.sh"), snippet("Backup", null));

        assertThat(SessionJournalCommandInfos.findSnippetName(snippets, "./deploy.sh")).isEqualTo("Deploy web01");
        assertThat(SessionJournalCommandInfos.findSnippetName(snippets, "/opt/bin/deploy.sh")).isEqualTo("Deploy web01");
        assertThat(SessionJournalCommandInfos.findSnippetName(snippets, "deploy")).isEqualTo("Deploy web01");
        assertThat(SessionJournalCommandInfos.findSnippetName(snippets, "backup")).isEqualTo("Backup");
        assertThat(SessionJournalCommandInfos.findSnippetName(snippets, "links")).isNull();
        assertThat(SessionJournalCommandInfos.findSnippetName(snippets, " ")).isNull();
    }
}
